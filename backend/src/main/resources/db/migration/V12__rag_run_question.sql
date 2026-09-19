ALTER TABLE rag_runs
    ADD COLUMN question text;

UPDATE rag_runs r
SET question = m.content
FROM messages m
WHERE m.id = r.user_message_id;
