-- Each conversation/checkpoint/stream key must have exactly one owner (ADR-23).
-- If historical duplicates exist, STOP migration and resolve ownership manually.
-- Do not silently delete or merge user data to satisfy this invariant.
ALTER TABLE user_conversations ADD UNIQUE KEY uk_conversation_id (conversation_id);
