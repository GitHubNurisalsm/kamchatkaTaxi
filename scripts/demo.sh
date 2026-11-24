#!/usr/bin/env bash
# Сценарий для защиты: все 8 шагов алгоритма на одной поездке по Бишкеку.
# Требуется запущенное приложение с профилем demo:
#   mvn spring-boot:run -Dspring-boot.run.profiles=demo
# Использование: ./scripts/demo.sh            (по умолчанию http://localhost:8080)
#                BASE=http://host:port ./scripts/demo.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
U="$BASE/api/v1"
H='Content-Type: application/json'

# Достаёт поле из JSON: json id  |  json driver.id
json() {
  python3 -c '
import json, sys
raw = sys.stdin.read()
if not raw.strip():
    sys.exit(1)   # запрос уже завершился ошибкой и вывел её
d = json.loads(raw)
for k in sys.argv[1].split("."):
    d = d.get(k) if isinstance(d, dict) else None
print("" if d is None else d)
' "$1"
}

# Печать таблицы «куда идут деньги» из ответа settlement / preview
money_table() {
  python3 -c '
import json, sys
d = json.load(sys.stdin)
print("  Цена: %.2f %s" % (d["amount"], d["currency"]))
for i in d["items"]:
    print("  %-18s%-24s%9.2f%7s%%" % (i["name"], i["calculation"], i["amount"], i["sharePercent"]))
print("  %-42s%9.2f%7s%%" % ("Остаётся водителю", d["driverNet"], d["driverSharePercent"]))
'
}

# Запрос с проверкой HTTP-кода: call METHOD URL [curl-аргументы...]
call() {
  local method=$1 url=$2; shift 2
  local out code
  out=$(curl -sS -X "$method" "$url" -w $'\n%{http_code}' "$@")
  code=${out##*$'\n'}
  out=${out%$'\n'*}
  if [[ $code -ge 400 ]]; then
    echo "ОШИБКА $code: $method $url" >&2
    echo "$out" >&2
    exit 1
  fi
  printf '%s' "$out"
}

step() { printf '\n\033[1m%s\033[0m\n' "$*"; }

if ! curl -sf -o /dev/null "$U/cities"; then
  echo "Сервис недоступен по $BASE." >&2
  echo "Запустите: mvn spring-boot:run -Dspring-boot.run.profiles=demo" >&2
  exit 1
fi

step "Слайд 10 — калькулятор: поездка за 699 ₽ в Москве"
call GET "$U/settlements/preview?city=MOSCOW&tariff=ECONOM&price=699&km=13.5&parkRate=0.03" | money_table

step "Шаги 1–2. Пассажир и цена: площадь Ала-Тоо → Asia Mall"
# Уникальный номер на каждый запуск: секунды + случайное число (два запуска в одну секунду не совпадут)
PHONE="+99655$(printf '%07d' $(( ($(date +%s) * 32768 + RANDOM) % 10000000 )))"
PASSENGER=$(call POST "$U/passengers" -H "$H" -d "{\"fullName\":\"Демо Пассажир\",\"phone\":\"$PHONE\"}" | json id)
QUOTE_JSON=$(call POST "$U/quotes" -H "$H" \
  -d '{"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":42.8559,"lon":74.5860},"tariff":"ECONOM"}')
QUOTE=$(printf '%s' "$QUOTE_JSON" | json id)
echo "  Пассажир id=$PASSENGER"
printf '%s' "$QUOTE_JSON" | python3 -c '
import json, sys
q = json.load(sys.stdin); b = q["breakdown"]; s = q["surge"]
print("  Маршрут %s км, %s мин; k = %s (пины %s / машины %s)" % (q["distanceKm"], q["durationMin"], s["k"], s["pins"], s["freeCars"]))
print("  P = подача %s + поездка %s + опции %s = %s -> %s %s" % (b["supply"], b["ride"], b["options"], b["totalExact"], q["total"], q["currency"]))
'

step "Шаг 3. Заказ (повтор с тем же Idempotency-Key вернёт тот же заказ)"
KEY="demo-$(date +%s)"
ORDER=$(call POST "$U/orders" -H "$H" -H "X-Passenger-Id: $PASSENGER" -H "Idempotency-Key: $KEY" \
  -d "{\"quoteId\":\"$QUOTE\"}" | json id)
AGAIN=$(call POST "$U/orders" -H "$H" -H "X-Passenger-Id: $PASSENGER" -H "Idempotency-Key: $KEY" \
  -d "{\"quoteId\":\"$QUOTE\"}" | json id)
if [[ $ORDER == "$AGAIN" ]]; then SAME="да"; else SAME="НЕТ"; fi
echo "  Заказ $ORDER; повтор вернул тот же: $SAME"

step "Шаг 4. Подбор водителя (раунд венгерского алгоритма)"
call POST "$U/dispatch/run" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for g in d["groups"]:
    print("  %s/%s: заказов %s, водителей %s, подача %s мин (жадно было бы %s)" % (
        g["city"], g["tariff"], g["orders"], g["drivers"], g["totalPickupMin"], g["greedyTotalPickupMin"]))
if not d["groups"]:
    print("  (заказ уже распределил фоновый раунд)")
'
DRIVER=$(call GET "$U/orders/$ORDER" -H "X-Passenger-Id: $PASSENGER" | json driver.id)
if [[ -z $DRIVER ]]; then
  echo "  Водитель не назначен: нет свободных демо-водителей рядом. Запущен ли профиль demo?" >&2
  exit 1
fi
echo "  Назначен водитель id=$DRIVER"

step "Шаги 5–6. Подача и поездка"
for a in accept arrive start complete; do
  printf '  %-9s → %s\n' "$a" "$(call POST "$U/driver/orders/$ORDER/$a" -H "X-Driver-Id: $DRIVER" | json status)"
done

step "Шаги 7–8. Куда ушли деньги"
call GET "$U/driver/orders/$ORDER/settlement" -H "X-Driver-Id: $DRIVER" | money_table

echo
echo "Готово. Swagger: $BASE/swagger-ui.html"
