#!/bin/bash
# 复制为 scripts/services.local.sh 后生效。
# 约定：
# - start_custom_services: 在 start-all.sh 末尾执行
# - stop_custom_services: 在 stop-all.sh 开始执行
# - print_custom_summary: 可选，打印额外服务状态

start_custom_services() {
  # 示例：启动一个本地服务并写日志
  # nohup bash -lc "cd /path/to/your-service && ./run.sh" >"${ROOT_DIR}/.runtime/your-service.log" 2>&1 &
  :
}

stop_custom_services() {
  # 示例：停止本地服务
  # pkill -f "your-service-keyword" || true
  :
}

print_custom_summary() {
  # 示例：打印状态
  # printf '  - your-service: %s\n' "$(curl -s http://127.0.0.1:9999/health 2>/dev/null || echo unavailable)"
  :
}
