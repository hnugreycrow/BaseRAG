import { describe, expect, it } from "vitest";
import { showVersion, type Exchange } from "./conversations";

describe("conversation answer versions", () => {
  it("switches displayed persisted versions without changing their audit data", () => {
    const exchange: Exchange = {
      id: "u1",
      question: "问题",
      state: "complete",
      activeVersion: 1,
      versions: [
        {
          id: "a1",
          answer: "旧回答",
          content: "旧回答",
          status: "COMPLETED",
          sources: [],
          citations: [],
        },
        {
          id: "a2",
          answer: "新回答",
          content: "新回答",
          status: "COMPLETED",
          active: true,
          sources: [],
          citations: [],
        },
      ],
    };
    exchange.answer = exchange.versions[1];

    showVersion(exchange, 0);

    expect(exchange.answer?.answer).toBe("旧回答");
    expect(exchange.versions[1]?.answer).toBe("新回答");
  });
});
