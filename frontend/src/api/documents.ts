import { http } from "./http";

export type DocumentRecord = {
  id: string;
  name: string;
  status: string;
  errorCode: string | null;
  chunkCount: number;
  createdAt: string;
};

export type ChunkRecord = {
  id: string;
  chunkIndex: number;
  heading: string;
  lineStart: number;
  lineEnd: number;
  characterCount: number;
  preview: string;
};

export type ChunkDetail = Omit<ChunkRecord, "preview"> & {
  documentId: string;
  versionId: string;
  content: string;
};

export type ImportDocumentResult = {
  documentId: string;
  status: string;
  chunkCount: number;
};

export async function listDocuments(knowledgeBaseId: string) {
  const { data } = await http.get<DocumentRecord[]>(
    `/knowledge-bases/${knowledgeBaseId}/documents`,
  );
  return data;
}

export async function importDocument(knowledgeBaseId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  const { data } = await http.post<ImportDocumentResult>(
    `/knowledge-bases/${knowledgeBaseId}/documents`,
    body,
  );
  return data;
}

export async function createDocumentChunks(
  knowledgeBaseId: string,
  documentId: string,
) {
  const { data } = await http.post<ImportDocumentResult>(
    `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks`,
  );
  return data;
}

export async function renameDocument(
  knowledgeBaseId: string,
  documentId: string,
  name: string,
) {
  const { data } = await http.patch<DocumentRecord>(
    `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    { name },
  );
  return data;
}

export async function deleteDocument(
  knowledgeBaseId: string,
  documentId: string,
) {
  await http.delete(
    `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
  );
}

export async function listChunks(knowledgeBaseId: string, documentId: string) {
  const { data } = await http.get<ChunkRecord[]>(
    `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks`,
  );
  return data;
}

export async function getChunk(
  knowledgeBaseId: string,
  documentId: string,
  chunkId: string,
) {
  const { data } = await http.get<ChunkDetail>(
    `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks/${chunkId}`,
  );
  return data;
}

export function statusLabel(status: string) {
  return (
    (
      {
        READY: "可检索",
        UPLOADED: "待分块",
        FAILED: "分块失败",
        PROCESSING: "分块中",
      } as Record<string, string>
    )[status] || status
  );
}
