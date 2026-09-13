<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 });
const paths: Record<string, string[]> = {
  chat: [
    "M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z",
  ],
  edit: [
    "M12 20H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h8",
    "m16 3 5 5-10 10-5 1 1-5L17 2",
    "m14 5 5 5",
  ],
  panel: [
    "M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z",
    "M9 3v18",
  ],
  arrow: ["M12 19V5", "m5 12 7-7 7 7"],
  arrowRight: ["M5 12h14", "m12 5 7 7-7 7"],
  arrowLeft: ["M19 12H5", "m12 19-7-7 7-7"],
  chevron: ["m9 5 7 7-7 7"],
  down: ["m6 9 6 6 6-6"],
  plus: ["M12 5v14", "M5 12h14"],
  close: ["m6 6 12 12", "M18 6 6 18"],
  search: [
    "M21 21l-4.5-4.5",
    "M19 10.5a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0Z",
  ],
  grid: ["M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z"],
  file: [
    "M14 2H5a1 1 0 0 0-1 1v18a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V8Z",
    "M14 2v6h6",
    "M8 13h8",
    "M8 17h5",
  ],
  book: [
    "M12 4v17",
    "M12 5C9 3 5 3 2 4v16c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1Z",
  ],
  database: [
    "M4 6c0 2 3.6 3.5 8 3.5S20 8 20 6s-3.6-3.5-8-3.5S4 4 4 6Z",
    "M4 6v6c0 2 3.6 3.5 8 3.5s8-1.5 8-3.5V6",
    "M4 12v6c0 2 3.6 3.5 8 3.5s8-1.5 8-3.5v-6",
  ],
  upload: [
    "M12 16V3",
    "m7 8 5-5 5 5",
    "M3 15v5a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-5",
  ],
  refresh: [
    "M20 7A9 9 0 0 0 5 4L2 7",
    "M2 2v5h5",
    "M4 17a9 9 0 0 0 15 3l3-3",
    "M17 17h5v5",
  ],
  check: ["m5 12 4 4L19 6"],
  clock: ["M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z", "M12 7v5l3 2"],
  alert: ["m12 3 10 18H2Z", "M12 9v4", "M12 17h.01"],
  link: [
    "m10 13 4-4",
    "m7 14-2 2a3 3 0 0 0 4 4l4-4a3 3 0 0 0 0-4",
    "m17 10 2-2a3 3 0 0 0-4-4l-4 4a3 3 0 0 0 0 4",
  ],
  copy: ["M9 9h12v12H9z", "M15 5V3H3v12h2"],
};
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path
      v-for="(path, index) in paths[name] || paths.chat"
      :key="index"
      :d="path"
    />
  </svg>
</template>
