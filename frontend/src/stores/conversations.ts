import { computed, reactive } from "vue";
import {
  cancelGeneration,
  createConversation,
  deleteConversation as deleteConversationApi,
  getConversation,
  listConversations,
  regenerateMessageStream,
  renameConversation as renameConversationApi,
  retryMessageStream,
  sendMessageStream,
  type Answer,
  type AssistantMessage,
  type StreamEvent,
} from "../api/index";

export type Exchange = {
  id: string;
  question: string;
  state: "pending" | "complete" | "failed" | "cancelled";
  answer?: Answer;
  versions: Answer[];
  activeVersion: number;
  assistantMessageId?: string;
  generationId?: string;
  error?: string;
};

export type Conversation = { id: string; title: string; exchanges: Exchange[] };

export const chatState = reactive({
  conversations: [] as Conversation[],
  activeId: "",
  draft: "",
  loading: false,
  error: "",
});

const controllers = new Map<string, AbortController>();

export const activeConversation = computed(() =>
  chatState.conversations.find((item) => item.id === chatState.activeId),
);

export const isAsking = computed(
  () =>
    activeConversation.value?.exchanges.some(
      (item) => item.state === "pending",
    ) || false,
);

function answerFrom(message: AssistantMessage): Answer {
  return {
    ...message,
    answer: message.content,
    sources: message.sources || [],
    citations: message.citations || [],
  };
}

function stateFrom(message: AssistantMessage): Exchange["state"] {
  if (message.status === "COMPLETED") return "complete";
  if (message.status === "CANCELLED") return "cancelled";
  if (message.status === "FAILED") return "failed";
  return "pending";
}

export async function loadConversations(query = "") {
  const rows = await listConversations(query);
  const existing = new Map(
    chatState.conversations.map((item) => [item.id, item]),
  );
  chatState.conversations = rows.map(
    (row) =>
      existing.get(row.id) || { id: row.id, title: row.title, exchanges: [] },
  );
  for (const row of rows) {
    const item = chatState.conversations.find((value) => value.id === row.id);
    if (item) item.title = row.title;
  }
}

export function newConversation() {
  chatState.activeId = "";
  chatState.draft = "";
  chatState.error = "";
}

export async function selectConversation(id: string) {
  chatState.activeId = id;
  chatState.draft = "";
  chatState.loading = true;
  chatState.error = "";
  try {
    const detail = await getConversation(id);
    const conversation: Conversation = {
      id: detail.id,
      title: detail.title,
      exchanges: detail.turns.map((turn) => {
        const versions = turn.assistantVersions.map(answerFrom);
        let activeVersion = turn.assistantVersions.findIndex(
          (item) => item.id === turn.activeAssistantId,
        );
        if (activeVersion < 0) activeVersion = Math.max(0, versions.length - 1);
        const message = turn.assistantVersions[activeVersion];
        return {
          id: turn.user.id,
          question: turn.user.content,
          state: message ? stateFrom(message) : "failed",
          answer: versions[activeVersion],
          versions,
          activeVersion,
          assistantMessageId: message?.id,
          generationId:
            message &&
            (message.status === "PENDING" || message.status === "STREAMING")
              ? message.id
              : undefined,
          error: message?.errorMessage || undefined,
        };
      }),
    };
    const index = chatState.conversations.findIndex((item) => item.id === id);
    if (index >= 0) chatState.conversations[index] = conversation;
    else chatState.conversations.unshift(conversation);
  } catch (error) {
    chatState.error = error instanceof Error ? error.message : "无法加载会话";
    throw error;
  } finally {
    chatState.loading = false;
  }
}

function placeholder(): Answer {
  return { answer: "", sources: [], citations: [], status: "PENDING" };
}

async function consume(
  conversation: Conversation,
  exchange: Exchange,
  run: (
    signal: AbortSignal,
    onEvent: (event: StreamEvent) => void,
  ) => Promise<void>,
) {
  const controller = new AbortController();
  controllers.set(conversation.id, controller);
  exchange.state = "pending";
  exchange.error = undefined;
  const current = placeholder();
  exchange.versions.push(current);
  exchange.activeVersion = exchange.versions.length - 1;
  exchange.answer = current;
  try {
    await run(controller.signal, (event) => {
      if (event.name === "started") {
        exchange.assistantMessageId = event.data.assistantMessageId;
        exchange.generationId = event.data.generationId;
      } else if (event.name === "delta") {
        current.answer += event.data.text || "";
        current.content = current.answer;
        current.status = "STREAMING";
      } else if (event.name === "reset") {
        current.answer = "";
        current.content = "";
      } else if (event.data.assistantMessage) {
        const finalAnswer = answerFrom(event.data.assistantMessage);
        exchange.versions[exchange.activeVersion] = finalAnswer;
        exchange.answer = finalAnswer;
        exchange.assistantMessageId = event.data.assistantMessage.id;
        exchange.generationId = undefined;
        exchange.state = stateFrom(event.data.assistantMessage);
        exchange.error =
          event.data.message ||
          event.data.assistantMessage.errorMessage ||
          undefined;
      }
    });
    if (exchange.state === "pending") throw new Error("流式响应意外结束");
  } catch (error) {
    if (exchange.state === "pending") {
      exchange.state = controller.signal.aborted ? "cancelled" : "failed";
      exchange.error = controller.signal.aborted
        ? "生成已停止"
        : error instanceof Error
          ? error.message
          : "生成失败，请重试";
    }
  } finally {
    controllers.delete(conversation.id);
    void loadConversations();
  }
}

export async function sendQuestion() {
  const question = chatState.draft.trim();
  if (!question || question.length > 2000 || isAsking.value) return;
  let conversation = activeConversation.value;
  if (!conversation) {
    const created = await createConversation(question.slice(0, 200));
    conversation = { id: created.id, title: created.title, exchanges: [] };
    chatState.conversations.unshift(conversation);
    chatState.activeId = created.id;
  }
  const clientMessageId = crypto.randomUUID();
  const exchange: Exchange = {
    id: clientMessageId,
    question,
    state: "pending",
    versions: [],
    activeVersion: 0,
  };
  conversation.exchanges.push(exchange);
  chatState.draft = "";
  await consume(conversation, exchange, (signal, onEvent) =>
    sendMessageStream(
      conversation!.id,
      clientMessageId,
      question,
      signal,
      onEvent,
    ),
  );
}

export async function retryExchange(exchange: Exchange) {
  const conversation = activeConversation.value;
  if (!conversation || !exchange.assistantMessageId || isAsking.value) return;
  const previous = exchange.assistantMessageId;
  await consume(conversation, exchange, (signal, onEvent) =>
    retryMessageStream(
      conversation.id,
      previous,
      crypto.randomUUID(),
      signal,
      onEvent,
    ),
  );
}

export async function regenerateExchange(exchange: Exchange) {
  const conversation = activeConversation.value;
  if (!conversation || !exchange.assistantMessageId || isAsking.value) return;
  const previous = exchange.assistantMessageId;
  await consume(conversation, exchange, (signal, onEvent) =>
    regenerateMessageStream(
      conversation.id,
      previous,
      crypto.randomUUID(),
      signal,
      onEvent,
    ),
  );
}

export async function stopGeneration(exchange: Exchange) {
  const conversation = activeConversation.value;
  if (!conversation || !exchange.generationId) return;
  try {
    await cancelGeneration(conversation.id, exchange.generationId);
  } catch {
    controllers.get(conversation.id)?.abort();
  }
}

export function showVersion(exchange: Exchange, index: number) {
  if (
    index < 0 ||
    index >= exchange.versions.length ||
    exchange.state === "pending"
  )
    return;
  exchange.activeVersion = index;
  exchange.answer = exchange.versions[index];
  const selected = exchange.answer;
  if (selected) {
    exchange.assistantMessageId = selected.id;
    exchange.state =
      selected.status === "COMPLETED"
        ? "complete"
        : selected.status === "CANCELLED"
          ? "cancelled"
          : "failed";
    exchange.error = selected.errorMessage || undefined;
  }
}

export async function renameConversation(id: string, title: string) {
  const updated = await renameConversationApi(id, title);
  const conversation = chatState.conversations.find((item) => item.id === id);
  if (conversation) conversation.title = updated.title;
}

export async function removeConversation(id: string) {
  await deleteConversationApi(id);
  chatState.conversations = chatState.conversations.filter(
    (item) => item.id !== id,
  );
  if (chatState.activeId === id) newConversation();
}
