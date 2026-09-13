CREATE TABLE conversations (
    id uuid PRIMARY KEY,
    title varchar(200) NOT NULL,
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    summarized_through_turn integer NOT NULL DEFAULT 0 CHECK (summarized_through_turn >= 0),
    summary_revision integer NOT NULL DEFAULT 0 CHECK (summary_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE messages (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    client_request_id uuid,
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    turn_index integer NOT NULL CHECK (turn_index > 0),
    variant_index integer NOT NULL DEFAULT 0 CHECK (variant_index >= 0),
    active boolean NOT NULL DEFAULT true,
    reply_to_id uuid REFERENCES messages(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    content text NOT NULL DEFAULT '',
    retrieval_query text,
    sources jsonb NOT NULL DEFAULT '[]'::jsonb,
    citations jsonb NOT NULL DEFAULT '[]'::jsonb,
    model_info jsonb,
    error_code varchar(80),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (conversation_id, role, turn_index, variant_index),
    UNIQUE (conversation_id, client_request_id),
    CHECK ((role = 'USER' AND variant_index = 0 AND reply_to_id IS NULL AND status = 'COMPLETED')
        OR (role = 'ASSISTANT' AND variant_index > 0 AND reply_to_id IS NOT NULL))
);

CREATE UNIQUE INDEX messages_one_active_assistant_idx
    ON messages(conversation_id, turn_index)
    WHERE role = 'ASSISTANT' AND active;
CREATE INDEX messages_conversation_turn_idx
    ON messages(conversation_id, turn_index, role, variant_index);

CREATE TABLE generation_attempts (
    id uuid PRIMARY KEY,
    assistant_message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    attempt_index integer NOT NULL CHECK (attempt_index > 0),
    reason varchar(30) NOT NULL CHECK (reason IN ('PRIMARY', 'PROVIDER_FALLBACK', 'CITATION_REPAIR')),
    model_id text NOT NULL,
    provider text NOT NULL,
    model text NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    content text NOT NULL DEFAULT '',
    finish_reason text,
    error_code varchar(80),
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (assistant_message_id, attempt_index)
);

CREATE INDEX conversations_updated_idx ON conversations(updated_at DESC, id);
CREATE INDEX generation_attempts_message_idx ON generation_attempts(assistant_message_id, attempt_index);
