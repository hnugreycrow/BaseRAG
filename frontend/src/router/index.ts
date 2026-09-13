import { createRouter, createWebHistory } from "vue-router";
import { adminRoutes } from "./routes/admin";
import { chatRoutes } from "./routes/chat";

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: "/", redirect: "/chat" },
    ...adminRoutes,
    ...chatRoutes,
    { path: "/:pathMatch(.*)*", redirect: "/chat" },
  ],
});

router.afterEach((to) => {
  document.title = `${String(to.meta.title || "工作台")} · JAgent`;
});
