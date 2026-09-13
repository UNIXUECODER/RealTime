-- Deleting a channel currently fails: api_keys.channel_id references channels(id)
-- with default (NO ACTION) behavior, and every channel has an active key. An API key
-- cannot meaningfully exist without its channel — CASCADE matches that ownership
-- relationship, rather than requiring the application to manually delete keys first.
ALTER TABLE api_keys DROP CONSTRAINT api_keys_channel_id_fkey;
ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_channel_id_fkey
        FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE CASCADE;
