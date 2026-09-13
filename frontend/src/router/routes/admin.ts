import type { RouteRecordRaw } from "vue-router";
export const adminRoutes: RouteRecordRaw[] = [
  {
    path: "/admin",
    name: "admin-knowledge-bases",
    component: () => import("../../views/AdminView.vue"),
    meta: { title: "知识库管理", section: "知识库管理" },
  },
  {
    path: "/admin/documents",
    redirect: "/admin",
  },
];
