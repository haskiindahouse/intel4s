# Pipeline Post-Mortem: [название задачи]

**Дата:** YYYY-MM-DD
**Проект:** [имя проекта]
**Спека:** docs/specs/[name].md
**Исход:** FAILED / PARTIAL / REWORK_NEEDED

## Что произошло
1-2 предложения.

## Failure taxonomy
- [ ] **spec_failure** — спека неполная, двусмысленная, без SHALL NOT
- [ ] **agent_drift** — агент вышел за scope
- [ ] **context_loss** — забыл invariants, compaction
- [ ] **tool_failure** — CI, dependency, env
- [ ] **approach_failure** — технический подход неверный
- [ ] **integration_failure** — работает изолированно, ломается при merge
- [ ] **bdd_gap** — scenarios не покрыли edge case

## Контекст агента
- Какие файлы читал
- Где зациклился / отклонился
- Сколько итераций

## Root cause
[Заполняет Oracle или Human]

## Action items
- [ ] [Файл в pipeline-kit/templates/]: [что изменить]
