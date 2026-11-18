-- V3: usernames are unique ignoring case ("Asha" and "asha" are the same user).
-- The case of the stored value is kept for display; lookups compare lower(username).
CREATE UNIQUE INDEX uq_app_user_username_lower ON app_user (lower(username));
