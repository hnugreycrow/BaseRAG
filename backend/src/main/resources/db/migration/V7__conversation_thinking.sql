ALTER TABLE conversations
    ADD COLUMN thinking_enabled boolean NOT NULL DEFAULT false;

ALTER TABLE messages
    ADD COLUMN thinking_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN reasoning_content text NOT NULL DEFAULT '';

ALTER TABLE generation_attempts
    ADD COLUMN reasoning_content text NOT NULL DEFAULT '';
