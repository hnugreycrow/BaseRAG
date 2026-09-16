CREATE TABLE intent_nodes (
    id uuid PRIMARY KEY,
    parent_id uuid REFERENCES intent_nodes(id) ON DELETE RESTRICT,
    name varchar(100) NOT NULL,
    description varchar(300) NOT NULL DEFAULT '',
    examples_json text NOT NULL DEFAULT '[]',
    kind varchar(16),
    tool_name varchar(128),
    enabled boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT intent_nodes_kind_check CHECK (kind IS NULL OR kind IN ('KB', 'MCP', 'SYSTEM')),
    CONSTRAINT intent_nodes_tool_check CHECK (
        (kind = 'MCP' AND tool_name IS NOT NULL)
        OR (kind IS DISTINCT FROM 'MCP' AND tool_name IS NULL)
    )
);

CREATE INDEX intent_nodes_parent_order_idx ON intent_nodes(parent_id, sort_order, id);

CREATE TABLE intent_node_knowledge_bases (
    id uuid PRIMARY KEY,
    node_id uuid NOT NULL REFERENCES intent_nodes(id) ON DELETE CASCADE,
    knowledge_base_id uuid NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    UNIQUE (node_id, knowledge_base_id)
);

