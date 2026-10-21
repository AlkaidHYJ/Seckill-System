# _v4_reconcile.ps1 - v4 每轮对账（临时工装，压测结束后删除）
$MYSQL = 'flash-sale-mysql'
$REDIS = 'flash-sale-redis'
$MQ    = 'flash-sale-rabbitmq'

Write-Host '--- MQ ---'
docker exec $MQ rabbitmqctl list_queues name messages | Select-String 'seckill' | ForEach-Object { Write-Host ("  " + $_) }

Write-Host '--- MySQL ---'
$q = @"
SELECT CONCAT('valid_orders=', COALESCE(SUM(quantity),0)) FROM orders WHERE activity_id=2 AND status IN ('UNPAID','PAID');
SELECT CONCAT('total_orders=', COUNT(*)) FROM orders WHERE activity_id=2;
SELECT CONCAT('distinct_users=', COUNT(DISTINCT user_id)) FROM orders WHERE activity_id=2;
SELECT CONCAT('dup_users=', COUNT(*)) FROM (SELECT user_id FROM orders WHERE activity_id=2 GROUP BY user_id HAVING COUNT(*)>1) t;
SELECT CONCAT('available_stock=', available_stock, ' total_stock=', total_stock) FROM seckill_activity WHERE id=2;
SELECT CONCAT('neg_stock=', COUNT(*)) FROM seckill_activity WHERE id=2 AND available_stock < 0;
SELECT CONCAT('status_', status, '=', COUNT(*)) FROM orders WHERE activity_id=2 GROUP BY status;
"@
docker exec -e MYSQL_PWD=111111 $MYSQL mysql -uroot seckill -N -e $q | ForEach-Object { Write-Host "  $_" }

Write-Host '--- Redis ---'
Write-Host ("  redis_stock=" + (docker exec $REDIS redis-cli GET seckill:stock:2))
Write-Host ("  redis_creating=" + (docker exec $REDIS redis-cli ZCARD seckill:creating))