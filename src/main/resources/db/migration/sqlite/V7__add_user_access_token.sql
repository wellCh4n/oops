ALTER TABLE "user" ADD COLUMN access_token varchar(255);
CREATE UNIQUE INDEX idx_user_access_token ON "user"(access_token);
