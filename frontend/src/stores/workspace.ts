import { computed, reactive } from "vue";
import {
  errorMessage,
  listDocuments,
  listKnowledgeBases,
  type KnowledgeBase,
  type DocumentRecord,
} from "../api/index";

export const workspace = reactive({
  knowledgeBases: [] as KnowledgeBase[],
  documents: [] as DocumentRecord[],
  loading: false,
  initialized: false,
  error: "",
});
export const readyDocuments = computed(() =>
  workspace.documents.filter((doc) => doc.status === "READY"),
);
let connecting: Promise<void> | undefined;

export async function refreshDocuments() {
  const groups = await Promise.all(
    workspace.knowledgeBases.map((base) => listDocuments(base.id)),
  );
  workspace.documents = groups.flat();
}

export function connectWorkspace(): Promise<void> {
  if (connecting) return connecting;
  workspace.loading = true;
  workspace.error = "";
  connecting = (async () => {
    try {
      const bases = await listKnowledgeBases();
      workspace.knowledgeBases = bases;
      await refreshDocuments();
      workspace.initialized = true;
    } catch (error) {
      workspace.error = errorMessage(error);
    } finally {
      workspace.loading = false;
      connecting = undefined;
    }
  })();
  return connecting;
}
