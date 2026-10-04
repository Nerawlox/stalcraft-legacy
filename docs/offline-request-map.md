# Первичная карта запросов клиента

Статический спутник [учёта патча](offline-patch-audit.md), 4 октября 2026. Здесь перечислены видимые отправители и покрытие именно общественного `ServerPacketHandler`. Это не полный каталог всего протокола и не доказательство отсутствия обработчиков в других модах.

## Два пути и проблема обёртки

В базовом `gloomyfolken.bundle.common.core.dfaj` есть два пути: `sendToServer()` и `sendClientToBackend()`. В `base/dxne.java:13` первый отправляет `xael(request)`, а второй (`:21`) отправляет `xael(amxp(request))`. Общественный handler не распаковывает `amxp`, поэтому даже известный внутренний request в такой обёртке попадает в неподдержанную ветку. Наш [LocalPacketBridge](../src/LocalPacketBridge.java) распаковывает обёртку и сохраняет исходного отправителя; уже проверена обёрнутая перезарядка.

Не путать этот `amxp` в default package с классом `gloomyfolken.mods.stalker.clans.amxp`, который отвечает за другую подсистему.

В самом `dfaj` нет общей реализации `processServer`: dispatch централизован в `xael`. Регистрация типа/ID, существующая кнопка интерфейса и отправка сообщения — разные свидетельства; ни одно не гарантирует успешной серверной операции.

## Что есть в офлайн-handler

В `patch/ServerPacketHandler.java:102` есть **17 веток по типам**:

- Инвентарный superclass `nvsj`; внутри распознаются `txdm`, `supg`, `rald`, `yvqy`, `sdot`.
- Перезарядка `cfal`, hitscan `rrmj`, выстрел `rakn`, fire mode `tgqy`, unload `fokk`, melee `ozfd`.
- Десять запросов GameObjects: set, update, kill request, history, teleport to camera, interact, switch lootable, configure lootable, marker update, place attempt.

Наличие ветки не означает законченную механику: fire-mode handler пустой, а hitscan/shoot/inventory требуют проверок состояния и полномочий. Entity action и creative slot подключены отдельными vanilla hooks; они не дополнительные ветки этого dispatch. Неизвестный тип только журналируется (`patch/ServerPacketHandler.java:136`).

## Что искать и проверять дальше

Источники ниже — неизменённый `decompiled/base`. Имена приведены по конкретным отправителям; во всём каталоге они могут быть обфусцированы и неоднозначны.

| Семья | Свидетельство запроса | Покрытие этим handler и следующий вопрос |
| --- | --- | --- |
| Оружие / обвесы | `weapon/owap.java:380`, `weapon/zwbc.java:269`, `foet.java:64` | Есть перечисленные ветки. Проверить фактическое действие, ответ и серверные ограничения каждого режима. |
| Объекты / редактор GO | `mods/gameobjects/client/EditHandler.java:598`, `GOGameHandler.java:168` | Десять веток. Проверить права редактирования, изменения в чанках и сохранение. |
| ПДА / карта / профиль / задания | `RequestExtraMapData` в `mods/pda/client/tab/PdaMap.java:38`, `tvat` в `mods/pda/client/ClientHandler.java:23`, `PacketMapTeleport` в `MapComponent.java:390` | Типизированных веток нет. Проследить загрузку района/заданий/профиля и выяснить, какие данные сохранились локально. Часть профильных вызовов использует backend route. |
| NPC / торговля / банк | `noppes/npcs/client/gui/GuiAdvancedTrader.java:462`, `GuiTradepacks.java:158`, `GuiInventoryBank.java:92` | Нет веток trade/bank в этом helper. Исследовать сохранившиеся NPC controllers, валюты, цены, ограничения, данные диалогов и выдачу наград. |
| Почта | `EnumPlayerPacket.Mail*`, `GuiMailbox`, `GuiMailmanSend`; `NoppesUtilPlayer.java:182` | Отдельный канал `CNPCs Player`, а не доказанное отсутствие mail handler в dfaj. Нужно отдельно проследить этот канал и persistence. |
| Группы / кланы | `gloomyfolken/mods/party/PartyMod.java:50`, `gloomyfolken/mods/stalker/clans/ClansMod.java:175` | Нет соответствующих типизированных веток; есть оба транспорта. Нужно восстановить состояние состава/приглашений и права, если эти механики входят в целевой релиз. |
| Дополнительное движение | `jyta` в `net/smart/moving/SmartMovingSelf.java:125` | Нет такой ветки здесь; наш Smart Moving event handler — отдельный слой. Проследить репликацию поз/состояний и проверить двумя клиентами. |
| Шум / мутанты / спавн | `PacketClientNoise` в `stalker/misc/qlfw.java:240`; mutant tuning `mobs/client/tuning/gui/RegionEdit.java:204` | Нет соответствующих веток. Исследовать AI listeners, sound/noise rules и управление регионами спавна; регистрация сущности не доказывает работающий AI. |
| Другие редакторы / регионы / звук | `mods/regions/client/GuiPresetBlock.java:56`, `mods/sound/client/GuiEditSource.java:60`, NPC scheme GUI | Нет их веток здесь, кроме указанного GO editor. Проверить дополнительные каналы и необходимость редакторов для передачи проекта разработчикам. |
| Трупы / ragdoll / физика | `physics/ragdolls/entity/EntityRagdollCorpse.java:311`, `RagdollsHooks.java:52` | Нет веток в данном helper. Проследить обработчики соответствующих модов, loot трупа и принадлежность physics state серверу. |

Каждую строку следующего прохода нужно превратить в проверяемый сценарий: действие игрока → запрос → серверная проверка → изменение состояния → ответ игроку/наблюдателям → запись → восстановление после перезапуска. Это поможет отделять доступный код от недостающих данных и новых реализаций.

Общее число зарегистрированных пакетов не следует использовать как процент готовности: registry включает ответы клиенту, requests, вложенные типы и другие каналы. Синтаксический поиск вызовов не даёт полного направления/наследования обфусцированных классов.
