ALTER TABLE knowledge_bases
    ADD COLUMN embedding_model_id text,
    ADD COLUMN embedding_provider text;

ALTER TABLE document_versions
    ADD COLUMN embedding_model_id text,
    ADD COLUMN embedding_provider text;

UPDATE knowledge_bases
SET embedding_model_id = 'qwen-emb-8b', embedding_provider = 'siliconflow'
WHERE embedding_model = 'Qwen/Qwen3-Embedding-8B'
  AND embedding_dimensions = 1536;

UPDATE document_versions
SET embedding_model_id = 'qwen-emb-8b', embedding_provider = 'siliconflow'
WHERE embedding_model = 'Qwen/Qwen3-Embedding-8B'
  AND embedding_dimensions = 1536;

DO $$
DECLARE unknown_bindings text;
BEGIN
    SELECT string_agg(source || ':' || model || '/' || dimensions, ', ')
    INTO unknown_bindings
    FROM (
        SELECT 'knowledge_base' AS source, embedding_model AS model,
               embedding_dimensions AS dimensions
        FROM knowledge_bases
        WHERE embedding_model IS NOT NULL AND embedding_model_id IS NULL
        UNION
        SELECT 'document_version', embedding_model, embedding_dimensions
        FROM document_versions
        WHERE embedding_model_id IS NULL
    ) unmatched;
    IF unknown_bindings IS NOT NULL THEN
        RAISE EXCEPTION 'Unmapped legacy embedding bindings: %', unknown_bindings;
    END IF;
END $$;

ALTER TABLE knowledge_bases
    ADD CONSTRAINT knowledge_base_embedding_identity_check CHECK (
        (embedding_model IS NULL AND embedding_dimensions IS NULL
            AND embedding_model_id IS NULL AND embedding_provider IS NULL)
        OR (embedding_model IS NOT NULL AND embedding_dimensions IS NOT NULL
            AND embedding_model_id IS NOT NULL AND embedding_provider IS NOT NULL)
    );

ALTER TABLE document_versions
    ALTER COLUMN embedding_model_id SET NOT NULL,
    ALTER COLUMN embedding_provider SET NOT NULL;

ALTER TABLE knowledge_bases
    ADD CONSTRAINT knowledge_base_embedding_identity_unique
    UNIQUE (id, embedding_model_id, embedding_provider, embedding_model, embedding_dimensions);

ALTER TABLE document_versions
    ADD CONSTRAINT document_version_embedding_identity_fk
    FOREIGN KEY (knowledge_base_id, embedding_model_id, embedding_provider,
                 embedding_model, embedding_dimensions)
    REFERENCES knowledge_bases(id, embedding_model_id, embedding_provider,
                                embedding_model, embedding_dimensions);
