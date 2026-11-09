INSERT INTO app_users (username,email,mobile,password,user_role,user_status,created_at,pg_id,must_change_password) VALUES ('admin','user1@gmail.com','8000000001','$2a$12$PglICIFlJWV.FtYFFJAjkeWPvbB6jpxWSXdcONNN4mZJr3MkLnLjG','SUPER_ADMIN','ACTIVE',NOW(),null,false);
INSERT INTO pgs (name,owner_name,mobile,email,address,city,state,license_key,plan,license_start,license_expiry,total_floors,active) VALUES ('My PG','Ramesh Kumar','9876543210','agro.main@gmail.com','Market Road','Hindupur','Andhra Pradesh','PG-001','PRO','2024-04-01','2025-03-31',4,true);

-- Dropdown options (see com.pg.lookup)
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('STAY_TYPE','APARTMENT','Apartment','Flat',1,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('STAY_TYPE','PG','PG','Room',2,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('STAY_TYPE','HOSTEL','Hostel','Dorm',3,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('STAY_TYPE','COLIVING','Co-Living','Room',4,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('STAY_TYPE','STUDIO','Studio','Room',5,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('GENDER_POLICY','CO_LIVING','Co-Living / Unisex',null,1,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('GENDER_POLICY','GENTS','Gents',null,2,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('GENDER_POLICY','LADIES','Ladies',null,3,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('GENDER_POLICY','FAMILY','Family',null,4,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('EXPENSE_TYPE','MARKET','Family',null,4,true);
INSERT INTO lookup_values (category,code,label,extra,sort_order,active) VALUES ('EXPENSE_TYPE','STAFF_SALARY','Family',null,4,true);
