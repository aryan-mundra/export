-- Source tables for the tests: the groups of table-groups-test.yml.
CREATE TABLE IF NOT EXISTS tg_person (person_id INT PRIMARY KEY, birth_year INT);
CREATE TABLE IF NOT EXISTS tg_person_name (name_id INT PRIMARY KEY, profile_ref INT REFERENCES tg_person (person_id), first_name VARCHAR(50));
CREATE TABLE IF NOT EXISTS tg_person_address (address_no INT PRIMARY KEY, owner_id INT REFERENCES tg_person (person_id), city VARCHAR(50));
CREATE TABLE IF NOT EXISTS tg_address_country (id INT PRIMARY KEY, addr_fk INT REFERENCES tg_person_address (address_no), country VARCHAR(2));
CREATE TABLE IF NOT EXISTS tg_entity (entity_id VARCHAR(10) PRIMARY KEY, label VARCHAR(50));
CREATE TABLE IF NOT EXISTS tg_entity_code (id INT PRIMARY KEY, ent_ref VARCHAR(10) REFERENCES tg_entity (entity_id), code VARCHAR(20));
INSERT INTO tg_person VALUES (1, 1970), (2, NULL);
INSERT INTO tg_person_name VALUES (1, 1, 'John'), (2, 1, 'Johnny'), (3, 2, 'Maria');
INSERT INTO tg_person_address VALUES (10, 1, 'London'), (11, 1, 'Paris');
INSERT INTO tg_address_country VALUES (1, 10, 'GB'), (2, 11, 'FR');
INSERT INTO tg_entity VALUES ('E1', 'ACME');
INSERT INTO tg_entity_code VALUES (1, 'E1', 'ISIN1'), (2, 'E1', 'ISIN2');
