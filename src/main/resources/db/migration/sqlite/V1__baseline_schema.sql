CREATE TABLE application (
    id varchar(255) NOT NULL,
    created_time timestamp,
    description varchar(255),
    name varchar(255),
    namespace varchar(255),
    owner varchar(255),
    PRIMARY KEY (id),
    UNIQUE (name)
);

CREATE TABLE application_build_config (
    id varchar(255) NOT NULL,
    created_time timestamp,
    application_name varchar(255),
    build_image varchar(255),
    docker_file varchar(255),
    environment_configs TEXT,
    namespace varchar(255),
    repository varchar(255),
    source_type varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE application_environment (
    id varchar(255) NOT NULL,
    created_time timestamp,
    application_name varchar(255),
    environment_name varchar(255),
    namespace varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE application_performance_config (
    id varchar(255) NOT NULL,
    created_time timestamp,
    application_name varchar(255),
    environment_configs TEXT,
    namespace varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE application_service_config (
    id varchar(255) NOT NULL,
    created_time timestamp,
    application_name varchar(255),
    environment_configs TEXT,
    namespace varchar(255),
    port integer,
    PRIMARY KEY (id)
);

CREATE TABLE domain (
    id varchar(255) NOT NULL,
    created_time timestamp,
    cert_mode varchar(255),
    cert_not_after timestamp,
    cert_pem TEXT,
    cert_subject varchar(255),
    description varchar(255),
    host varchar(255),
    https boolean,
    key_pem TEXT,
    PRIMARY KEY (id),
    UNIQUE (host)
);

CREATE TABLE environment (
    id varchar(255) NOT NULL,
    build_storage_class varchar(255),
    image_repository_password varchar(255),
    image_repository_url varchar(255),
    image_repository_username varchar(255),
    api_server_token TEXT,
    api_server_url varchar(255),
    name varchar(255),
    work_namespace varchar(255),
    PRIMARY KEY (id),
    UNIQUE (name)
);

CREATE TABLE external_account (
    id varchar(255) NOT NULL,
    created_time timestamp,
    email varchar(255),
    provider varchar(255),
    provider_user_id varchar(255),
    user_id varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE namespace (
    id varchar(255) NOT NULL,
    created_time timestamp,
    description varchar(255),
    name varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE pipeline (
    id varchar(255) NOT NULL,
    created_time timestamp,
    namespace varchar(255),
    application_name varchar(255),
    status varchar(255),
    artifact TEXT,
    environment varchar(255),
    branch varchar(255),
    publish_type varchar(255),
    publish_repository varchar(255),
    deploy_mode varchar(255),
    operator_id varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE "user" (
    id varchar(255) NOT NULL,
    created_time timestamp,
    email varchar(255) NOT NULL,
    password varchar(255),
    role varchar(255),
    username varchar(255),
    PRIMARY KEY (id)
);
