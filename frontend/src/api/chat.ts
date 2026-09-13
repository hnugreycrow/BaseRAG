import { http } from "./http";

export type Source = {
  citationId: string;
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  chunkId: string;
  documentId: string;
  versionId: string;
  documentName: string;
  heading: string;
  lineStart: number;
  lineEnd: number;
  similarity: number;
  content: string;
};

export type ModelInfo = { id: string; provider: string; model: string };

export type Answer = {
  id?: string;
  replyToId?: string;
  turnIndex?: number;
  variantIndex?: number;
  active?: boolean;
  status?: "PENDING" | "STREAMING" | "COMPLETED" | "FAILED" | "CANCELLED";
  answer: string;
  content?: string;
  retrievalQuery?: string;
  sources: Source[];
  citations: string[];
  modelInfo?: ModelInfo | null;
  errorCode?: string | null;
  errorMessage?: string | null;
};

export type ConversationSummary = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};

export type AssistantMessage = Omit<Answer, "answer"> & {
  id: string;
  replyToId: string;
  turnIndex: number;
  variantIndex: number;
  active: boolean;
  status: "PENDING" | "STREAMING" | "COMPLETED" | "FAILED" | "CANCELLED";
  content: string;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
};

export type ConversationTurn = {
  user: { id: string; turnIndex: number; content: string; createdAt: string };
  assistantVersions: AssistantMessage[];
  activeAssistantId?: string | null;
};

export type ConversationDetail = ConversationSummary & {
  turns: ConversationTurn[];
};

export async function createConversation(title: string) {
  const { data } = await http.post<ConversationSummary>("/conversations", {
    title,
  });
  return data;
}

export async function listConversations(q = "", limit = 50) {
  const { data } = await http.get<ConversationSummary[]>("/conversations", {
    params: { q, limit },
  });
  return data;
}

export async function getConversation(id: string) {
  const { data } = await http.get<ConversationDetail>(`/conversations/${id}`);
  return data;
}

export async function renameConversation(id: string, title: string) {
  const { data } = await http.patch<ConversationSummary>(
    `/conversations/${id}`,
    {
      title,
    },
  );
  return data;
}

export async function deleteConversation(id: string) {
  await http.delete(`/conversations/${id}`);
}

export type StreamEvent = {
  name: "started" | "delta" | "reset" | "complete" | "cancelled" | "error";
  data: {
    schemaVersion: 1;
    text?: string;
    reason?: string;
    conversationId?: string;
    userMessageId?: string;
    assistantMessageId?: string;
    generationId?: string;
    turnIndex?: number;
    variantIndex?: number;
    assistantMessage?: AssistantMessage;
    code?: string;
    message?: string;
    requestId?: string;
    retryable?: boolean;
  };
};

export async function streamConversationMessage(
  path: string,
  body: object,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
) {
  const response = await fetch(`/api${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as {
      message?: string;
      requestId?: string;
    };
    throw new Error(
      error.message
        ? error.message + (error.requestId ? ` · 请求 ${error.requestId}` : "")
        : `服务请求失败（${response.status}）`,
    );
  }
  if (!response.body) throw new Error("浏览器未提供流式响应体");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
    let boundary: number;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let name = "message";
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) name = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
      }
      if (data.length && name !== "message") {
        const parsed = JSON.parse(data.join("\n")) as StreamEvent["data"];
        if (parsed.schemaVersion !== 1) throw new Error("不支持的流式事件版本");
        const eventName = name as StreamEvent["name"];
        onEvent({ name: eventName, data: parsed });
        if (
          eventName === "complete" ||
          eventName === "cancelled" ||
          eventName === "error"
        ) {
          await reader.cancel().catch(() => undefined);
          return;
        }
      }
    }
    if (done) break;
  }
}

export function sendMessageStream(
  conversationId: string,
  clientMessageId: string,
  content: string,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
) {
  return streamConversationMessage(
    `/conversations/${conversationId}/messages`,
    { clientMessageId, content },
    signal,
    onEvent,
  );
}

export function retryMessageStream(
  conversationId: string,
  assistantMessageId: string,
  clientRequestId: string,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
) {
  return streamConversationMessage(
    `/conversations/${conversationId}/messages/${assistantMessageId}/retry`,
    { clientRequestId },
    signal,
    onEvent,
  );
}

export function regenerateMessageStream(
  conversationId: string,
  assistantMessageId: string,
  clientRequestId: string,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
) {
  return streamConversationMessage(
    `/conversations/${conversationId}/messages/${assistantMessageId}/regenerate`,
    { clientRequestId },
    signal,
    onEvent,
  );
}

export async function cancelGeneration(
  conversationId: string,
  generationId: string,
) {
  await http.post(
    `/conversations/${conversationId}/generations/${generationId}/cancel`,
  );
}

// Compatibility helper for the phase-1 evaluation endpoint.
export async function askQuestion(question: string) {
  const { data } = await http.post<Answer>("/questions", { question });
  return data;
}
