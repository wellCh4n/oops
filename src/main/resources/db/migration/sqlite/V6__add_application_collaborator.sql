CREATE TABLE application_collaborator (
    id varchar(255) NOT NULL,
    created_time timestamp,
    namespace varchar(255),
    application_name varchar(255),
    user_id varchar(255),
    PRIMARY KEY (id),
    UNIQUE (namespace, application_name, user_id)
);

CREATE INDEX idx_application_collaborator_app
    ON application_collaborator (namespace, application_name);

CREATE INDEX idx_application_collaborator_user
    ON application_collaborator (user_id);
