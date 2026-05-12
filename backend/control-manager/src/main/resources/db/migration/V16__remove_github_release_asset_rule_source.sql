UPDATE rule_set
SET source_type = 'HTTP_FILE'
WHERE source_type = 'GITHUB_RELEASE_ASSET';
