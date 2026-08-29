UPDATE `application_runtime_spec`
SET `health_check` = NULL
WHERE `health_check` IS NOT NULL
  AND TRIM(`health_check`) = '';

UPDATE `application_runtime_spec`
SET `health_check` = JSON_OBJECT(
    'liveness', CAST(`health_check` AS JSON),
    'readiness', JSON_OBJECT(
        'enabled', false,
        'path', '/',
        'initialDelaySeconds', 30,
        'periodSeconds', 10,
        'timeoutSeconds', 3,
        'failureThreshold', 3
    )
)
WHERE `health_check` IS NOT NULL
  AND JSON_VALID(`health_check`)
  AND JSON_EXTRACT(`health_check`, '$.liveness') IS NULL
  AND JSON_EXTRACT(`health_check`, '$.readiness') IS NULL;
