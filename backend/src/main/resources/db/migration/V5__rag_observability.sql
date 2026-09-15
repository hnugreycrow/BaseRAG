CREATE TABLE rag_runs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    request_id varchar(64) NOT NULL,
    conversation_id uuid REFERENCES conversations(id) ON DELETE SET NULL,
    user_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    assistant_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    execution_mode varchar(30) NOT NULL,
    model_id text,
    provider text,
    model text,
    candidate_count integer NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    evidence_count integer NOT NULL DEFAULT 0 CHECK (evidence_count >= 0),
    degraded boolean NOT NULL DEFAULT false,
    error_code varchar(80),
    started_at timestamptz NOT NULL,
    first_token_at timestamptz,
    completed_at timestamptz,
    total_ms bigint CHECK (total_ms IS NULL OR total_ms >= 0),
    end_to_end_ttft_ms bigint CHECK (end_to_end_ttft_ms IS NULL OR end_to_end_ttft_ms >= 0),
    model_ttft_ms bigint CHECK (model_ttft_ms IS NULL OR model_ttft_ms >= 0)
);

CREATE UNIQUE INDEX rag_runs_assistant_message_idx
    ON rag_runs(assistant_message_id) WHERE assistant_message_id IS NOT NULL;
CREATE INDEX rag_runs_owner_started_idx ON rag_runs(owner_id, started_at DESC, id DESC);
CREATE INDEX rag_runs_started_idx ON rag_runs(started_at DESC, id DESC);
CREATE INDEX rag_runs_filter_idx ON rag_runs(status, execution_mode, model, started_at DESC);

CREATE TABLE rag_stage_runs (
    id uuid PRIMARY KEY,
    rag_run_id uuid NOT NULL REFERENCES rag_runs(id) ON DELETE CASCADE,
    stage_name varchar(40) NOT NULL,
    sub_question_id varchar(40),
    sequence_no integer NOT NULL CHECK (sequence_no > 0),
    status varchar(20) NOT NULL CHECK (status IN ('SUCCESS', 'DEGRADED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    input_count integer CHECK (input_count IS NULL OR input_count >= 0),
    output_count integer CHECK (output_count IS NULL OR output_count >= 0),
    model_id text,
    provider text,
    model text,
    reason_code varchar(80),
    error_code varchar(80),
    started_at timestamptz NOT NULL,
    first_token_at timestamptz,
    completed_at timestamptz NOT NULL,
    elapsed_ms bigint NOT NULL CHECK (elapsed_ms >= 0),
    ttft_ms bigint CHECK (ttft_ms IS NULL OR ttft_ms >= 0),
    UNIQUE (rag_run_id, sequence_no)
);

CREATE INDEX rag_stage_runs_run_sequence_idx
    ON rag_stage_runs(rag_run_id, sequence_no);
