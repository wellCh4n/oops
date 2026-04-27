ALTER TABLE application_build_config ADD COLUMN docker_file_config TEXT;

UPDATE application_build_config SET docker_file_config = '{"type":"BUILTIN","path":"' || docker_file || '"}' WHERE docker_file IS NOT NULL AND docker_file != '';

UPDATE application_build_config SET docker_file_config = '{"type":"BUILTIN","path":"Dockerfile"}' WHERE docker_file_config IS NULL;

ALTER TABLE application_build_config DROP COLUMN docker_file;
