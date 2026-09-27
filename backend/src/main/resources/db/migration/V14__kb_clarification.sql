ALTER TABLE messages ADD COLUMN clarification_json text;
ALTER TABLE messages ADD COLUMN clarification_context_json text;

CREATE TABLE conversation_clarifications (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    original_message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    assistant_message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    resume_message_id uuid REFERENCES messages(id) ON DELETE CASCADE,
    generation_id uuid REFERENCES messages(id) ON DELETE CASCADE,
    context_json text NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'RESUMING', 'RESOLVED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX conversation_clarifications_one_open
    ON conversation_clarifications(conversation_id) WHERE status IN ('PENDING', 'RESUMING');
