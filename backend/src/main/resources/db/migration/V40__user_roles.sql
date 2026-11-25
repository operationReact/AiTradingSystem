ALTER TABLE app_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'VIEWER';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_role CHECK (role IN ('VIEWER', 'TRADER', 'ADMIN'));

CREATE INDEX ix_app_user_role ON app_user(role);
