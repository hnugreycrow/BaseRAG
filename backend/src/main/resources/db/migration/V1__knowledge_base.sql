CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_bases (
    id uuid PRIMARY KEY, name varchar(200) NOT NULL,
    embedding_model text, embedding_dimensions integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((embedding_model IS NULL AND embedding_dimensions IS NULL)
        OR (embedding_model IS NOT NULL AND embedding_dimensions > 0)),
    UNIQUE (id, embedding_model, embedding_dimensions)
);
INSERT INTO knowledge_bases (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', '默认知识库');

CREATE TABLE documents (
    id uuid PRIMARY KEY,
    knowledge_base_id uuid NOT NULL REFERENCES knowledge_bases(id),
    name varchar(255) NOT NULL,
    active_version_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, knowledge_base_id)
);
CREATE TABLE document_versions (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents(id),
    knowledge_base_id uuid NOT NULL,
    file_hash varchar(64) NOT NULL,
    storage_key text NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    error_code varchar(80),
    parser_version varchar(30) NOT NULL,
    chunker_version varchar(30) NOT NULL,
    embedding_model text NOT NULL,
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, document_id),
    UNIQUE (id, embedding_dimensions),
    FOREIGN KEY (document_id, knowledge_base_id) REFERENCES documents(id, knowledge_base_id),
    FOREIGN KEY (knowledge_base_id, embedding_model, embedding_dimensions)
        REFERENCES knowledge_bases(id, embedding_model, embedding_dimensions)
);
ALTER TABLE documents ADD CONSTRAINT active_version_owned
    FOREIGN KEY (active_version_id, id) REFERENCES document_versions(id, document_id);
CREATE TABLE document_chunks (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    version_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL CHECK (length(content) > 0),
    heading text NOT NULL,
    line_start integer NOT NULL CHECK (line_start > 0),
    line_end integer NOT NULL CHECK (line_end >= line_start),
    embedding_dimensions integer NOT NULL,
    embedding vector NOT NULL,
    UNIQUE (version_id, chunk_index),
    FOREIGN KEY (version_id, document_id) REFERENCES document_versions(id, document_id),
    FOREIGN KEY (version_id, embedding_dimensions) REFERENCES document_versions(id, embedding_dimensions),
    CHECK (vector_dims(embedding) = embedding_dimensions),
    CHECK (vector_norm(embedding) > 0)
);
CREATE INDEX documents_kb_idx ON documents(knowledge_base_id);
CREATE INDEX versions_document_idx ON document_versions(document_id);

