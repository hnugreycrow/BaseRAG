ALTER TABLE conversations ADD COLUMN summary_text text NOT NULL DEFAULT '';

-- Preserve old summaries in readable field order before removing their JSONB column.
-- Unexpected legacy shapes are retained verbatim rather than silently discarded.
UPDATE conversations AS conversation
SET summary_text =
    CASE
        WHEN jsonb_typeof(conversation.summary -> 'goalsAndTopics') = 'array'
         AND jsonb_typeof(conversation.summary -> 'factsAndConstraints') = 'array'
         AND jsonb_typeof(conversation.summary -> 'decisionsAndPreferences') = 'array'
         AND jsonb_typeof(conversation.summary -> 'entitiesAndReferences') = 'array'
         AND jsonb_typeof(conversation.summary -> 'openItems') = 'array'
        THEN COALESCE((
            SELECT string_agg(field.label || '：' || section.items, '；' ORDER BY field.position)
            FROM (VALUES
                (1, 'goalsAndTopics', '话题'),
                (2, 'factsAndConstraints', '事实与约束'),
                (3, 'decisionsAndPreferences', '决定与偏好'),
                (4, 'entitiesAndReferences', '实体与指代'),
                (5, 'openItems', '未解决事项')
            ) AS field(position, key, label)
            CROSS JOIN LATERAL (
                SELECT string_agg(item.value, '、' ORDER BY item.ordinality) AS items
                FROM jsonb_array_elements_text(conversation.summary -> field.key)
                    WITH ORDINALITY AS item(value, ordinality)
            ) AS section
            WHERE section.items IS NOT NULL
        ), '')
        ELSE conversation.summary::text
    END
WHERE conversation.summary <> '{}'::jsonb;

ALTER TABLE conversations DROP COLUMN summary;

COMMENT ON COLUMN conversations.summary_text IS
    'Rolling topic summary used for conversation context';
