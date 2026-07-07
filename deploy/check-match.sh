#!/bin/bash
echo '=== mtt_match 最近5场 ==='
docker exec chexuan-mysql mysql -uroot -pFz2025qq@ chexuan_game -e "SELECT id,name,club_id,status,FROM_UNIXTIME(start_time/1000) start,entry_fee,entry_currency,reward_type,robot_count,participants FROM mtt_match ORDER BY id DESC LIMIT 5" 2>/dev/null
echo '=== mtt 容器最近日志 ==='
cd /home/fzcx/chexuan && docker compose logs --tail 30 mtt | tail -30
