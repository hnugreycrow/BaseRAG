<script setup lang="ts">
import { onMounted } from "vue";
import { RouterView } from "vue-router";
import { connectWorkspace } from "./stores/workspace";

onMounted(() => {
  void connectWorkspace();
});
</script>

<template>
  <RouterView />
</template>

<style>
:root {
  font-family: Inter, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  color: #171717;
  background: #ffffff;
  font-synthesis: none;
  font-size: 15px;
  line-height: 1.6;
  --ink: #171717;
  --muted: #737373;
  --line: #e5e5e5;
  --canvas: #f7f7f8;
  --panel: #ffffff;
  --navy: #182133;
  --cyan: #10a37f;
  --blue: #3867ed;
  --el-color-primary: #3867ed;
  --el-color-success: #18a06a;
  --el-border-radius-base: 7px;
  --el-border-color: #dfe4ec;
  --el-text-color-primary: #172033;
  --el-text-color-regular: #526078;
  --el-font-size-base: 15px;
  --el-font-size-small: 14px;
}

* {
  box-sizing: border-box;
}
html,
body,
#app {
  height: 100%;
}
body {
  margin: 0;
  min-width: 320px;
  background: var(--canvas);
}
button,
input,
textarea,
select {
  font: inherit;
}
button,
a,
input,
textarea,
select {
  -webkit-tap-highlight-color: transparent;
}
button,
a {
  touch-action: manipulation;
}
button {
  color: inherit;
  cursor: pointer;
  border: 0;
  background: none;
}
button:disabled {
  cursor: not-allowed;
}
a {
  color: inherit;
  text-decoration: none;
}
h1,
h2,
h3,
p {
  margin: 0;
}
svg {
  flex-shrink: 0;
  vertical-align: middle;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.small {
  font-size: 13px;
}
.muted {
  color: var(--muted);
}
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 9px;
}
.icon-button:hover {
  background: #ececec;
}
.spinner {
  width: 15px;
  height: 15px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes blink {
  to {
    opacity: 0.3;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    transition: none !important;
    scroll-behavior: auto !important;
  }
}
</style>
