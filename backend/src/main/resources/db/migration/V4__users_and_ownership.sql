CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role varchar(10) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled boolean NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- This disabled row owns legacy data only until the first administrator is bootstrapped.
INSERT INTO users (id, username, display_name, password_hash, role, enabled)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '__legacy_owner__',
    'Legacy data owner',
    '!not-a-login-hash!',
    'USER',
    false
);

ALTER TABLE knowledge_bases ADD COLUMN owner_id uuid;
UPDATE knowledge_bases
SET owner_id = '00000000-0000-0000-0000-000000000002'
WHERE owner_id IS NULL;
ALTER TABLE knowledge_bases ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE knowledge_bases
    ADD CONSTRAINT knowledge_bases_owner_fk FOREIGN KEY (owner_id) REFERENCES users(id);
CREATE INDEX knowledge_bases_owner_created_idx
    ON knowledge_bases(owner_id, created_at DESC, id);

ALTER TABLE conversations ADD COLUMN owner_id uuid;
UPDATE conversations
SET owner_id = '00000000-0000-0000-0000-000000000002'
WHERE owner_id IS NULL;
ALTER TABLE conversations ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE conversations
    ADD CONSTRAINT conversations_owner_fk FOREIGN KEY (owner_id) REFERENCES users(id);
DROP INDEX conversations_updated_idx;
CREATE INDEX conversations_owner_updated_idx
    ON conversations(owner_id, updated_at DESC, id);
