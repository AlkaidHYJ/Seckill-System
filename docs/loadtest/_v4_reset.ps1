# _v4_reset.ps1 - v4 压测每轮复位 + 验证（临时工装，压测结束后删除）
$ErrorActionPreference = 'Continue'
$MYSQL = 'flash-sale-mysql'
$REDIS = 'flash-sale-redis'
$MQ    = 'flash-sale-rabbitmq'

Write-Host '--- MySQL reset ---'
docker exec -e MYSQL_PWD=111111 $MYSQL mysql -uroot seckill -e "UPDATE seckill_activity SET available_stock=100, total_stock=100 WHERE id=2; DELETE FROM orders WHERE activity_id=2; DELETE FROM stock_compensation WHERE activity_id=2; DELETE FROM reconciliation_record WHERE activity_id=2;"

Write-Host '--- Redis reset ---'
docker exec $REDIS redis-cli SET seckill:stock:2 100 | Out-Null
docker exec $REDIS redis-cli DEL seckill:creating | Out-Null
docker exec $REDIS sh -c "redis-cli --scan --pattern 'seckill:user:2:*' | xargs -r redis-cli DEL"

Write-Host '--- MQ purge ---'
docker exec $MQ rabbitmqctl purge_queue seckill.order.queue
docker exec $MQ rabbitmqctl purge_queue seckill.order.dlq

Write-Host '--- Verify reset ---'
$mysqlOut = docker exec -e MYSQL_PWD=111111 $MYSQL mysql -uroot seckill -N -e "SELECT CONCAT('available_stock=',available_stock) FROM seckill_activity WHERE id=2; SELECT CONCAT('orders=',COUNT(*)) FROM orders WHERE activity_id=2;"
$mysqlOut | ForEach-Object { Write-Host "  $_" }
Write-Host ("  redis_stock=" + (docker exec $REDIS redis-cli GET seckill:stock:2))
Write-Host ("  redis_creating=" + (docker exec $REDIS redis-cli ZCARD seckill:creating))
docker exec $MQ rabbitmqctl list_queues name messages | Select-String 'seckill' | ForEach-Object { Write-Host ("  mq " + $_) }