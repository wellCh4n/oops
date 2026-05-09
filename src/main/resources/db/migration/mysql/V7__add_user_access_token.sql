ALTER TABLE `user` ADD COLUMN `access_token` varchar(255) DEFAULT NULL;
ALTER TABLE `user` ADD UNIQUE KEY `uk_user_access_token` (`access_token`);
