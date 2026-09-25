ALTER TABLE rag_runs
    ADD COLUMN first_reasoning_ms bigint CHECK (first_reasoning_ms >= 0),
    ADD COLUMN first_answer_ms bigint CHECK (first_answer_ms >= 0);

ALTER TABLE rag_stage_runs
    ADD COLUMN parent_stage_id uuid,
    ADD COLUMN queue_ms bigint CHECK (queue_ms >= 0),
    ADD COLUMN attempt_id uuid,
    ADD COLUMN attempt_index integer CHECK (attempt_index > 0),
    ADD COLUMN first_reasoning_ms bigint CHECK (first_reasoning_ms >= 0),
    ADD COLUMN first_answer_ms bigint CHECK (first_answer_ms >= 0),
    ADD CONSTRAINT rag_stage_parent_not_self CHECK (parent_stage_id IS DISTINCT FROM id),
    ADD CONSTRAINT rag_stage_run_id_unique UNIQUE (rag_run_id, id),
    ADD CONSTRAINT rag_stage_parent_same_run FOREIGN KEY (rag_run_id, parent_stage_id)
        REFERENCES rag_stage_runs (rag_run_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX rag_stage_runs_parent_idx ON rag_stage_runs(rag_run_id, parent_stage_id);
