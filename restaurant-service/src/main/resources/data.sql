 -- 1. Switch to restaurant workspace context
SET search_path TO restaurant_service_schema;
 
 -- 2. Execute your seed statement
 -- Modern PostgreSQL idempotent syntax utilizing conflict targets
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('BURGER_01', 50) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('PIZZA_01', 30) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('FRIES_01', 100) ON CONFLICT (item_code) DO NOTHING;

-- Edge Case Verification: Low Stock Targets
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('SUSHI_02', 3) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('STEAK_02', 2) ON CONFLICT (item_code) DO NOTHING;

-- Saga Compensation Rollback Testing: Sold out states
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('TACO_02', 0) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('RAMEN_02', 0) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('ICE_CREAM_02', 0) ON CONFLICT (item_code) DO NOTHING;

-- 3. Verify the records were stored
SELECT * FROM restaurant_inventory;