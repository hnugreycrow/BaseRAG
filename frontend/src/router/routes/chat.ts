import type { RouteRecordRaw } from "vue-router";

export const chatRoutes: RouteRecordRaw[] = [
  {
    path: "/chat/:conversationId?",
    name: "chat",
    component: () => import("../../views/ChatView.vue"),
    meta: { title: "知识问答", section: "问答中心" },
  },
];
