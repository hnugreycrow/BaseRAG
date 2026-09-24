// Local interaction demo. No network requests or persistent account changes.
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const escapeText = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );
let toastTimer;
function toast(message) {
  $("#prototype-toast").textContent = message;
  $("#prototype-toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    $("#prototype-toast").hidden = true;
  }, 3500);
}
function stat(label, value, note, symbol) {
  return `<div class="overview-card"><div class="label">${label}<svg aria-hidden="true"><use href="#${symbol}"/></svg></div><div class="value">${value}</div><small>${note}</small></div>`;
}
const overview = `<div class="overview-grid">${stat("知识库", "3", "集中管理企业知识与资料", "books")}${stat("文档总数", "87", "已关联至 3 个知识库", "grid")}${stat("已启用意图", '2 <span class="status">已启用</span>', "最多支持 32 个问答节点", "nodes")}${stat("问答运行", "3", "2 次完成 · 1 次运行中", "chart")}</div>`;
$("#page-knowledge .page-head").insertAdjacentHTML("afterend", overview);
$("#page-knowledge .page-head h1").outerHTML =
  "<div><h1>知识库管理</h1><p>创建知识库，管理文档与向量模型。</p></div>";
$("#page-knowledge .panel").insertAdjacentHTML(
  "afterbegin",
  '<div class="knowledge-tabs"><button class="active" type="button">全部知识库 <span id="knowledge-total">3</span></button></div>',
);
$("#page-knowledge .table-wrap").style.paddingBottom = "8px";
$("#page-knowledge .toolbar").insertAdjacentHTML(
  "beforeend",
  '<button class="button" id="reset-knowledge" type="button">重置</button>',
);
$("#reset-knowledge").addEventListener("click", () => {
  const input = $("#page-knowledge .search input");
  input.value = "";
  input.dispatchEvent(new Event("input"));
});
$("#page-knowledge .toolbar .button.icon").addEventListener("click", () => {
  toast("示例知识库列表已刷新");
});
$("#page-knowledge").insertAdjacentHTML(
  "beforeend",
  '<p class="section-note">向量模型用于文档检索，创建后不可更换。打开知识库后可上传文档、查看分块和管理索引。</p>',
);
$$(".resource-mark, .model-mark, .user-mark").forEach((item) => {
  item.remove();
});
$$("#page-knowledge .model-copy small").forEach((item) => {
  item.className = "row-sub";
});
$$("#page-knowledge .date-cell small").forEach((item) => {
  item.className = "row-sub";
});
$$("#page-knowledge .row-actions .button").forEach((button) => {
  button.textContent = "管理文档";
});
$$("#page-users tbody tr").forEach((row, index) => {
  row
    .querySelector(".status")
    .classList.toggle("disabled", sampleUsers[index][3] === "已禁用");
});
$("#page-users .search input").addEventListener("input", () => {
  $("#page-users .pagination span").textContent =
    `共 ${$$("#page-users tbody tr").filter((row) => !row.hidden).length} 条`;
});
$("#page-traces .metric:nth-child(2) strong").textContent = "100%";
$("#page-traces .metric:nth-child(2)").title =
  "成功率按已结束运行计算；运行中不计入分母。";
$("#page-dashboard .page-head").outerHTML =
  '<header class="page-head"><div><h1>工作台</h1><p>知识资源与问答运行概览</p></div><button class="button" type="button" data-page="knowledge">管理知识库 →</button></header>';
$("#page-dashboard .quick-links").remove();
$("#page-dashboard .page-head").insertAdjacentHTML("afterend", overview);
$("#page-dashboard").insertAdjacentHTML(
  "beforeend",
  '<div class="dashboard-grid" style="margin-top:20px"><section class="panel"><div class="panel-head"><h2>知识资源</h2><span>3 个知识库</span></div><div class="task-item"><div>产品使用手册<small>42 份文档 · qwen3.7-text-embedding</small></div><button class="button text" data-open-library="产品使用手册">管理文档 →</button></div><div class="task-item"><div>人事制度<small>18 份文档 · qwen3.7-text-embedding</small></div><button class="button text" data-open-library="人事制度">管理文档 →</button></div><div class="task-item"><div>常见问题<small>27 份文档 · qwen3.7-text-embedding</small></div><button class="button text" data-open-library="常见问题">管理文档 →</button></div></section><section class="panel"><div class="panel-head"><h2>快捷操作</h2></div><div class="task-item"><div>准备知识<small>创建知识库并上传资料</small></div><button class="button text" data-dialog="knowledge">新建 →</button></div><div class="task-item"><div>配置意图<small>维护分类和检索范围</small></div><button class="button text" data-page="intent">配置 →</button></div><div class="task-item"><div>检查运行<small>查看问答状态与耗时</small></div><button class="button text" data-page="traces">查看 →</button></div></section></div>',
);

let dialogOpener;
function openDialog(id) {
  dialogOpener = document.activeElement;
  const backdrop = document.getElementById(`dialog-${id}`);
  backdrop.hidden = false;
  backdrop.querySelector("input, select, button")?.focus();
}
function closeDialogs() {
  $$(".dialog-backdrop").forEach((item) => {
    item.hidden = true;
  });
  dialogOpener?.focus();
}
$$(".dialog-backdrop").forEach((backdrop) => {
  backdrop.addEventListener("click", (event) => {
    if (event.target === backdrop) {
      closeDialogs();
    }
  });
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    closeMenu();
  }
});
document.addEventListener("keydown", (event) => {
  const dialog = $(".dialog-backdrop:not([hidden]) .dialog");
  if (!dialog) {
    return;
  }
  if (event.key === "Escape") {
    closeDialogs();
  }
  if (event.key === "Tab") {
    const controls = [
      ...dialog.querySelectorAll(
        'button:not(:disabled), input, select, textarea, [tabindex="0"]',
      ),
    ];
    const first = controls[0];
    const last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    }
    if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
});

const libraryDocuments = new Map([
  [
    "产品使用手册",
    [
      ["产品快速入门.pdf", "PDF", 86, "可检索", "今天 10:30"],
      ["账户与权限说明.pdf", "PDF", 124, "可检索", "今天 09:45"],
      ["API 接入指南.md", "Markdown", 218, "可检索", "昨天 17:20"],
      ["常见问题汇总.docx", "Word", 64, "索引中", "昨天 16:12"],
      ["数据导入规范.pdf", "PDF", "—", "解析失败", "昨天 15:40"],
    ],
  ],
  [
    "人事制度",
    [
      ["员工手册.pdf", "PDF", 56, "可检索", "今天 09:30"],
      ["年假申请流程.docx", "Word", 21, "可检索", "昨天 14:20"],
    ],
  ],
  ["常见问题", [["常见问题.md", "Markdown", 73, "可检索", "昨天 16:10"]]],
]);
let activeLibrary = "产品使用手册";
function renderDocuments() {
  const query = $("#document-search").value.trim().toLowerCase();
  const documents = libraryDocuments.get(activeLibrary) || [];
  const filtered = documents
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => row[0].toLowerCase().includes(query));
  $("#document-rows").innerHTML = filtered
    .map(
      ({ row, index }) =>
        `<tr><td><span class="row-name">${escapeText(row[0])}</span></td><td>${row[1]}</td><td>${row[2]}</td><td><span class="status ${row[3] === "解析失败" ? "failed" : row[3] !== "可检索" ? "running" : ""}">${row[3]}</span></td><td>${row[4]}</td><td><button class="button text" data-document="${index}">${row[3] === "解析失败" ? "查看原因" : "查看详情"}</button></td></tr>`,
    )
    .join("");
  $("#document-empty").hidden = filtered.length > 0;
  $("#document-count").textContent = `展示 ${filtered.length} 份示例文档`;
}
function openLibrary(name) {
  activeLibrary = name;
  $("#document-library-name").textContent = name;
  $("#document-search").value = "";
  showPage("documents");
  renderDocuments();
}
$("#document-search").addEventListener("input", renderDocuments);
$("#upload-files").addEventListener("change", (event) => {
  $("#upload-confirm").disabled = event.target.files.length === 0;
});
$("#upload-confirm").addEventListener("click", () => {
  const files = [...$("#upload-files").files];
  const documents = libraryDocuments.get(activeLibrary) || [];
  files.forEach((file) => {
    documents.unshift([
      file.name,
      escapeText(file.name.split(".").at(-1).toUpperCase()),
      "—",
      "待解析",
      "刚刚",
    ]);
  });
  libraryDocuments.set(activeLibrary, documents);
  renderDocuments();
  closeDialogs();
  toast(`已添加 ${files.length} 份文档到本次演示，未上传至服务器`);
  $("#upload-files").value = "";
  $("#upload-confirm").disabled = true;
});

const knowledgeName = $("#dialog-knowledge input");
const knowledgeModel = $("#dialog-knowledge select");
knowledgeModel.innerHTML =
  '<option value="">选择向量模型</option><option>qwen3.7-text-embedding</option><option>bge-m3</option>';
function validateKnowledge() {
  $("#dialog-knowledge .primary").disabled =
    !knowledgeName.value.trim() || !knowledgeModel.value;
}
knowledgeName.addEventListener("input", validateKnowledge);
knowledgeModel.addEventListener("change", validateKnowledge);
$("#dialog-knowledge .primary").addEventListener("click", () => {
  const name = knowledgeName.value.trim();
  if (
    $$("#page-knowledge tbody tr").some(
      (row) => row.querySelector(".row-name").textContent === name,
    )
  ) {
    toast("已存在同名知识库");
    return;
  }
  $("#page-knowledge tbody").insertAdjacentHTML(
    "beforeend",
    `<tr><td><span class="row-name">${escapeText(name)}</span></td><td>${escapeText(knowledgeModel.value)}</td><td>0 份</td><td>刚刚</td><td><div class="row-actions"><button class="button text" type="button">管理文档</button></div></td></tr>`,
  );
  libraryDocuments.set(name, []);
  const count = $$("#page-knowledge tbody tr").length;
  $("#knowledge-total").textContent = count;
  $("#page-knowledge .overview-card .value").textContent = count;
  $("#page-dashboard .overview-card .value").textContent = count;
  $(".nav-count").textContent = count;
  $("#page-knowledge .search input").value = "";
  $("#page-knowledge .search input").dispatchEvent(new Event("input"));
  closeDialogs();
  knowledgeName.value = "";
  validateKnowledge();
  toast("知识库已创建（仅本次演示有效）");
});

// The conversation owns its navigation and central reading column.
function updateShell() {
  const chat = !$("#page-chat").hidden;
  document.body.classList.toggle("chat-mode", chat);
  $$(".nav [data-page]").forEach((button) => {
    if (button.classList.contains("active")) {
      button.setAttribute("aria-current", "page");
    } else {
      button.removeAttribute("aria-current");
    }
  });
}
new MutationObserver(updateShell).observe($("#page-chat"), {
  attributes: true,
  attributeFilter: ["hidden"],
});
updateShell();
const composer = $("#page-chat textarea");
const send = $("#page-chat .primary");
composer.addEventListener("input", () => {
  send.disabled = !composer.value.trim();
});
function sendQuestion() {
  if (!composer.value.trim()) {
    return;
  }
  $("#page-chat .chat-empty").hidden = true;
  $("#chat-messages").insertAdjacentHTML(
    "beforeend",
    `<div class="chat-message">${escapeText(composer.value.trim())}</div><div class="chat-message answer">这是问答界面演示。接入服务后，回答及引用来源将在此处显示。当前不会调用模型或检索知识库。</div>`,
  );
  composer.value = "";
  send.disabled = true;
}
send.addEventListener("click", sendQuestion);
composer.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    sendQuestion();
  }
});
$("#page-chat .chat-composer-actions > .button:first-child").addEventListener(
  "click",
  (event) => {
    const enabled = event.currentTarget.getAttribute("aria-pressed") === "true";
    event.currentTarget.setAttribute("aria-pressed", String(!enabled));
    event.currentTarget.textContent = enabled
      ? "深度思考"
      : "深度思考 · 已开启";
  },
);
document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button) {
    return;
  }
  if (button.dataset.page) {
    showPage(button.dataset.page);
  }
  if (button.dataset.dialog) {
    openDialog(button.dataset.dialog);
  }
  if (button.hasAttribute("data-close")) {
    closeDialogs();
  }
  if (button.dataset.openLibrary) {
    openLibrary(button.dataset.openLibrary);
  }
  if (
    button.closest("#page-knowledge .row-actions") &&
    button.textContent === "管理文档"
  ) {
    openLibrary(button.closest("tr").querySelector(".row-name").textContent);
  }
  if (button.hasAttribute("data-document")) {
    const row =
      libraryDocuments.get(activeLibrary)[Number(button.dataset.document)];
    $("#document-detail-title").textContent = row[0];
    $("#document-detail-content").textContent =
      row[3] === "解析失败"
        ? "示例失败原因：PDF 未包含可提取的文本。请检查文件内容，或上传包含文本层的版本。"
        : `文件类型：${row[1]}；分块数：${row[2]}；状态：${row[3]}。此处为静态详情预览。`;
    openDialog("document-detail");
  }
  if (button.id === "new-chat") {
    $("#chat-messages").replaceChildren();
    $("#page-chat .chat-empty").hidden = false;
    composer.value = "";
    send.disabled = true;
    composer.focus();
  }
});
window.addEventListener("hashchange", () => {
  const key = location.hash.slice(1);
  if (titles[key]) {
    showPage(key);
  }
});
renderDocuments();
