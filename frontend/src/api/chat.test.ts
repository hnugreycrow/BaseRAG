import { afterEach, describe, expect, it, vi } from "vitest";
import { streamConversationMessage, type StreamEvent } from "./chat";

describe("conversation SSE parser", () => {
  afterEach(() => vi.restoreAllMocks());

  it("parses fragmented UTF-8 and versioned terminal events", async () => {
    const wire =
      'event: started\ndata: {"schemaVersion":1,"generationId":"g1"}\n\n' +
      'event: delta\ndata: {"schemaVersion":1,"text":"答案"}\n\n' +
      'event: complete\ndata: {"schemaVersion":1,"assistantMessage":{"id":"a1"}}\n\n';
    const encoded = new TextEncoder().encode(wire);
    const response = new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoded.slice(0, 73));
          controller.enqueue(encoded.slice(73, 79));
          controller.enqueue(encoded.slice(79));
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
    const events: StreamEvent[] = [];

    await streamConversationMessage(
      "/test",
      {},
      new AbortController().signal,
      (event) => events.push(event),
    );

    expect(events.map((event) => event.name)).toEqual([
      "started",
      "delta",
      "complete",
    ]);
    expect(events[1]?.data.text).toBe("答案");
  });

  it("stops reading as soon as a terminal event arrives", async () => {
    let cancelled = false;
    const wire =
      'event: complete\ndata: {"schemaVersion":1,"assistantMessage":{"id":"a1"}}\n\n';
    const response = new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode(wire));
        },
        cancel() {
          cancelled = true;
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
    const events: StreamEvent[] = [];

    await streamConversationMessage(
      "/test",
      {},
      new AbortController().signal,
      (event) => events.push(event),
    );

    expect(events.map((event) => event.name)).toEqual(["complete"]);
    expect(cancelled).toBe(true);
  });
});
