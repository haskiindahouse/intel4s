# Spec: [Название задачи]

**Дата:** YYYY-MM-DD
**Приоритет:** P0/P1/P2
**Модули:** [список затрагиваемых модулей из ARCHITECTURE.md]
**Estimated complexity:** S/M/L/XL

## Цель
Одно предложение: что должно произойти в результате.

## Контекст
Почему эта задача нужна. Background. Ссылки на issues.

## Scope
### В scope:
- Конкретный пункт 1
- Конкретный пункт 2

### ВНЕ scope:
- Что НЕ делаем (explicit exclusions)

## Технический подход
Как реализовать. Какие модули менять. Какие компоненты создать/изменить.

## Requirements & Scenarios (BDD-формализация)

### Requirement: [Название]
Краткое описание.

#### Scenario: Happy path
- **WHEN** пользователь делает X
- **THEN** система SHALL отрендерить Y
- **AND** система SHALL обновить Z
- **AND** система SHALL NOT добавлять лишнее

#### Scenario: Error case
- **WHEN** входные данные невалидны / компонент не загрузился
- **THEN** система SHALL показать fallback UI
- **AND** система SHALL NOT крэшить страницу

#### Scenario: Edge case
- **WHEN** [граничное условие: пустой массив, null, mobile viewport, SSR]
- **THEN** система SHALL [конкретное поведение]
- **AND** система SHALL NOT [то, что агент мог бы добавить "для удобства"]

## Acceptance Criteria
- [ ] Все Scenarios выше имеют тесты (Vitest)
- [ ] Тесты проходят: `npm run test`
- [ ] TypeCheck: `npm run typecheck`
- [ ] Lint: `npm run lint`
- [ ] Build: `npm run build`
- [ ] Existing tests не сломаны
- [ ] Файлы ≤ 300 строк (AI-8)

## Constraints
- Ссылки на ADR (docs/adr/)
- Ссылки на invariants из CLAUDE.md (AI-N)
- Специфические ограничения для этой задачи
