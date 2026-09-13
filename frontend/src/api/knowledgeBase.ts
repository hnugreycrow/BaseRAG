import { http } from "./http";

export type KnowledgeBase = {
  id: string;
  name: string;
  embeddingModel: string | null;
  embeddingDimensions: number | null;
  documentCount: number;
  createdAt: string;
};

export type EmbeddingModel = {
  id: string;
  provider: string;
  model: string;
  dimensions: number;
  defaultModel: boolean;
};

export async function listKnowledgeBases() {
  const { data } = await http.get<KnowledgeBase[]>("/knowledge-bases");
  return data;
}

export async function getKnowledgeBase(id: string) {
  const { data } = await http.get<KnowledgeBase>(`/knowledge-bases/${id}`);
  return data;
}

export async function listEmbeddingModels() {
  const { data } = await http.get<EmbeddingModel[]>(
    "/knowledge-bases/embedding-models",
  );
  return data;
}

export async function createKnowledgeBase(
  name: string,
  embeddingModelId: string,
) {
  const { data } = await http.post<KnowledgeBase>("/knowledge-bases", {
    name,
    embeddingModelId,
  });
  return data;
}

export async function renameKnowledgeBase(id: string, name: string) {
  const { data } = await http.patch<KnowledgeBase>(`/knowledge-bases/${id}`, {
    name,
  });
  return data;
}

export async function deleteKnowledgeBase(id: string) {
  await http.delete(`/knowledge-bases/${id}`);
}
