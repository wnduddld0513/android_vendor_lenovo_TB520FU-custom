#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Generates TB520FUCustomFeatures/res/values*/strings.xml for the "Custom
# features" app of the optional TB520FU customizations (game performance and
# Play Integrity Fix). English is the default; strings equal to English
# are left out of the translations so they fall back to it.
import os, re

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                   'TB520FUCustomFeatures', 'res')

KEYS = [
 ('app_name', None), ('app_summary', None),
 ('game_perf_title', None), ('game_perf_summary', None),
 ('game_perf_on', None), ('game_perf_off', None),
 ('game_perf_main_switch', None),
 ('game_mem_clean_title', None), ('game_mem_clean_summary', None),
 ('game_apps_category', None), ('game_app_add', None), ('game_app_remove', None),
 ('game_app_levels', None),
 ('game_cpu_category', None), ('game_gpu_category', None),
 ('game_perf_footer', None),
 ('game_custom_cpu_title', None),
 ('game_custom_gpu_title', None), ('game_custom_range_summary', None),
 ('game_custom_cpu_summary', None), ('game_custom_gpu_summary', None),
 ('game_custom_level', None),
 ('play_store_category', 'Play Store'),
 ('installer_spoof_title', None), ('installer_spoof_summary', None),
 ('reboot_title', None), ('reboot_now', None), ('reboot_later', None),
 ('integrity_category', None),
 ('integrity_fix_title', None), ('integrity_fix_summary', None),
 ('integrity_settings_title', None), ('integrity_settings_summary', None),
 ('specter_title', None), ('specter_summary', None),
 ('keybox_title', None), ('keybox_summary', None),
 ('teesim_title', None), ('teesim_summary', None),
 ('pif_title', None), ('pif_summary', None),
 ('integrity_reboot_message', None), ('integrity_reboot_off_message', None),
 ('integrity_status_category', None),
 ('keybox_state_title', None), ('keybox_state_installed', None),
 ('keybox_state_none', None), ('keybox_state_unavailable', None),
 ('keybox_state_revoked', None),
 ('keybox_source_title', None), ('keybox_serial_title', None),
 ('keybox_auto_title', None), ('keybox_auto_summary', None),
 ('keybox_auto_rotate_title', None), ('keybox_auto_rotate_summary', None),
 ('keybox_renew_title', None), ('keybox_renew_summary', None),
 ('keybox_renewing', None), ('keybox_renew_ok', None), ('keybox_renew_failed', None),
 ('keybox_clear_title', None), ('keybox_clear_summary', None),
 ('keybox_clear_confirm', None), ('keybox_cleared', None),
 ('keybox_pool_title', None), ('keybox_pool_summary', None), ('keybox_pool_none', None),
 ('teesim_state_title', None), ('teesim_on', None), ('teesim_off', None),
 ('teesim_keybox_title', None),
 ('teesim_targets_category', None), ('teesim_targets_all', None),
 ('teesim_targets_all_summary', None),
 ('teesim_target_add', None), ('teesim_target_remove', None),
 ('teesim_target_remove_confirm', None),
 ('teesim_auto_category', None),
 ('teesim_auto_title', None), ('teesim_auto_summary', None),
 ('teesim_add_all', None), ('teesim_add_all_summary', None),
 ('teesim_add_all_done', None),
 ('pif_state_title', None), ('pif_state_none', None),
 ('pif_state_user', None), ('pif_state_fetched', None),
 ('pif_patch_title', None), ('pif_fingerprint_title', None),
 ('pif_refresh_title', None), ('pif_refresh_summary', None),
 ('pif_refreshing', None), ('pif_refresh_ok', None), ('pif_refresh_failed', None),
 ('pif_clear_title', None), ('pif_clear_summary', None),
 ('pif_clear_confirm', None), ('pif_cleared', None),
]
ARRAYS = ['game_level_entries', 'game_cpu_level_summaries', 'game_gpu_level_summaries']

L = {}
L['en'] = dict(
 app_name='Custom features',
 app_summary='Game performance, Play Store identity',
 game_perf_title='Game performance',
 game_perf_on='On',
 game_perf_off='Off',
 game_perf_main_switch='Use per-app CPU and GPU settings',
 game_mem_clean_title='Free memory for games',
 game_mem_clean_summary='Closes background apps when an app from the list opens',
 game_apps_category='Apps',
 game_app_add='Add app',
 game_app_remove='Remove from list',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='Settings apply only while the app is on screen. Clocks return to normal when you leave the app. Thermal protection always stays active.',
 game_custom_cpu_title='CPU clock',
 game_custom_gpu_title='GPU limit',
 game_custom_range_summary='30–100 %',
 game_custom_cpu_summary='Custom %1$d%%',
 game_custom_gpu_summary='Custom %1$d%%',
 game_custom_level='Custom',
 play_store_category='Play Store',
 installer_spoof_title='Report the Play Store as the installer',
 installer_spoof_summary='Other apps see the Play Store as the installer of sideloaded apps, so apps that require a Play install keep working (for example Notein). Applies immediately; off by default',
 reboot_title='Restart required',
 reboot_now='Restart',
 reboot_later='Later',
 integrity_category='Integrity spoofing',
 integrity_fix_title='Play Integrity Fix',
 integrity_fix_summary='Keybox renewal, TEE simulator and the Play Integrity fingerprint in one switch; takes effect after a restart',
 integrity_settings_title='Play Integrity Fix settings',
 integrity_settings_summary='Keybox status and renewal, TEE simulator targets, Play Integrity fingerprint',
 specter_title='Specter (keybox renewal)',
 specter_summary='Fetches keyboxes from the Specter catalog, checks the Google revocation list and applies updates live; switching takes effect after a restart',
 keybox_title='Keybox (TEE simulator)',
 keybox_summary='Keybox renewal and storage; switching takes effect after a restart',
 teesim_title='TEE simulator',
 teesim_summary='Replaces hardware key attestation with the keybox; takes effect after a restart',
 pif_title='Play Integrity fingerprint',
 pif_summary='GMS build fingerprint and the DroidGuard key block; takes effect after a restart',
 integrity_reboot_message='This change is applied after the tablet restarts. Restart now?',
 integrity_reboot_off_message='Turning this off takes effect after the tablet restarts, and data that belongs to the feature (keyboxes, target list, fingerprint data) is removed then. Restart now?',
 integrity_status_category='Status',
 keybox_state_title='Keybox',
 keybox_state_installed='Installed',
 keybox_state_none='Not installed',
 keybox_state_unavailable='Service unavailable',
 keybox_state_revoked='Revoked by Google',
 keybox_source_title='Source',
 keybox_serial_title='Serial',
 keybox_auto_title='Automatic renewal',
 keybox_auto_summary='Checks the keybox catalog periodically and installs a fresh keybox; updates apply immediately',
 keybox_auto_rotate_title='Replace automatically when revoked',
 keybox_auto_rotate_summary='Swaps in the spare and fetches a fresh keybox when Google revokes the active one; off means only "Renew now" replaces it',
 keybox_renew_title='Renew now',
 keybox_renew_summary='Fetches the catalog and installs a keybox that is not revoked',
 keybox_renewing='Renewing…',
 keybox_renew_ok='Keybox installed: %1$s %2$s',
 keybox_renew_failed='Keybox renewal failed',
 keybox_clear_title='Delete keybox',
 keybox_clear_summary='Removes the installed keybox from the system',
 keybox_clear_confirm='Delete the installed keybox and all spare keyboxes? Hardware attestation is not spoofed until a new keybox is installed.',
 keybox_cleared='Keybox deleted',
 keybox_pool_title='Spare keyboxes',
 keybox_pool_summary='%1$d spare, %2$d revoked serials on record',
 keybox_pool_none='None',
 teesim_state_title='TEE simulator',
 teesim_on='On',
 teesim_off='Off',
 teesim_keybox_title='Keybox',
 teesim_targets_category='Target apps',
 teesim_targets_all='All apps',
 teesim_targets_all_summary='Spoofs the attestation of every app; off by default, only the list below is patched',
 teesim_target_add='Add app',
 teesim_target_remove='Tap to remove',
 teesim_target_remove_confirm='Remove this app from the target list?',
 teesim_auto_category='Automatic',
 teesim_auto_title='Add new apps automatically',
 teesim_auto_summary='Newly installed apps join the target list, uninstalled ones are dropped; checked on install and every five minutes',
 teesim_add_all='Add all installed apps',
 teesim_add_all_summary='Adds every third-party app that is installed right now',
 teesim_add_all_done='%1$d apps added',
 pif_state_title='Fingerprint data',
 pif_state_none='Not set',
 pif_state_user='User provided',
 pif_state_fetched='Fetched automatically',
 pif_patch_title='Security patch',
 pif_fingerprint_title='Fingerprint',
 pif_refresh_title='Fetch now',
 pif_refresh_summary='Downloads the certified build properties immediately',
 pif_refreshing='Fetching…',
 pif_refresh_ok='Fingerprint data updated',
 pif_refresh_failed='Fetch failed',
 pif_clear_title='Delete fingerprint data',
 pif_clear_summary='Removes the fetched and user provided data',
 pif_clear_confirm='Delete the fingerprint data?',
 pif_cleared='Fingerprint data deleted',
 game_perf_summary='CPU and GPU profiles per app; settings apply only while the app is on screen',

 game_level_entries=['Power saving', 'Balanced', 'Default'],

 game_cpu_level_summaries=['Lower clocks for less heat and longer play time', 'Caps the single core and the other cores to about 80 % for steady long sessions', 'Stock clocks; thermal protection still applies', 'Set the single core and multi core limits yourself'],

 game_gpu_level_summaries=['Lower graphics clock for less heat', 'Caps the graphics clock to about 80 % for less heat in long sessions', 'Stock graphics clock', 'Set the graphics clock limit yourself'],

)
L['ko'] = dict(
 app_name='커스텀 기능',
 app_summary='게임 성능, Play 스토어 표시',
 game_perf_title='게임 성능 관리',
 game_perf_on='사용',
 game_perf_off='사용 안함',
 game_perf_main_switch='앱별 CPU·GPU 설정 사용',
 game_mem_clean_title='게임용 메모리 정리',
 game_mem_clean_summary='목록의 앱을 열 때 백그라운드 앱을 종료합니다',
 game_apps_category='앱',
 game_app_add='앱 추가',
 game_app_remove='목록에서 삭제',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='설정은 해당 앱이 화면에 있을 때만 적용됩니다. 앱에서 나가면 클럭이 원래대로 돌아옵니다. 발열 보호는 항상 동작합니다.',
 game_custom_cpu_title='CPU 클럭',
 game_custom_gpu_title='GPU 제한',
 game_custom_range_summary='30~100%',
 game_custom_cpu_summary='사용자 설정 %1$d%%',
 game_custom_gpu_summary='사용자 설정 %1$d%%',
 game_custom_level='사용자 설정',
 play_store_category='Play 스토어',
 installer_spoof_title='Play 스토어에서 설치한 것으로 표시',
 installer_spoof_summary='다른 앱에 설치자를 Play 스토어로 알려줍니다. Play 설치가 필요한 앱(예: Notein)이 작동합니다. 즉시 적용되며 기본값은 꺼짐입니다',
 reboot_title='재시작 필요',
 reboot_now='다시 시작',
 reboot_later='나중에',
 integrity_category='무결성 위장',
 integrity_fix_title='Play Integrity Fix',
 integrity_fix_summary='키박스 자동 갱신, TEE 시뮬레이터, Play Integrity 지문을 한 스위치로 켭니다. 다시 시작한 뒤 적용됩니다',
 integrity_settings_title='Play Integrity Fix 설정',
 integrity_settings_summary='키박스 상태·갱신, TEE 시뮬레이터 대상, Play Integrity 지문',
 specter_title='Specter (키박스 자동 갱신)',
 specter_summary='Specter 카탈로그에서 키박스를 받아 폐기 여부를 확인하고 즉시 적용합니다. 스위치는 다시 시작한 뒤 적용됩니다',
 keybox_title='키박스 (TEE 시뮬레이터)',
 keybox_summary='키박스 저장·자동 갱신입니다. 스위치는 다시 시작한 뒤 적용됩니다',
 teesim_title='TEE 시뮬레이터',
 teesim_summary='하드웨어 키 증명을 키박스 서명으로 바꿉니다. 다시 시작한 뒤 적용됩니다',
 pif_title='Play Integrity 지문',
 pif_summary='GMS 빌드 지문과 DroidGuard 키 차단입니다. 다시 시작한 뒤 적용됩니다',
 integrity_reboot_message='이 변경은 태블릿을 다시 시작한 뒤 적용됩니다. 지금 다시 시작할까요?',
 integrity_reboot_off_message='기능을 끄면 다시 시작한 뒤 꺼지고, 그때 관련 데이터(키박스, 대상 목록, 지문 데이터)가 삭제됩니다. 지금 다시 시작할까요?',
 integrity_status_category='상태',
 keybox_state_title='키박스',
 keybox_state_installed='설치됨',
 keybox_state_none='설치 안 됨',
 keybox_state_unavailable='서비스를 사용할 수 없음',
 keybox_state_revoked='Google에서 폐기됨',
 keybox_source_title='출처',
 keybox_serial_title='일련번호',
 keybox_auto_title='자동 갱신',
 keybox_auto_summary='주기적으로 키박스 카탈로그를 확인해 새 키박스를 설치합니다. 갱신은 즉시 적용됩니다',
 keybox_auto_rotate_title='폐기되면 자동 교체',
 keybox_auto_rotate_summary='Google이 활성 키박스를 폐기하면 보관된 예비로 즉시 교체하고 새 키박스를 받아둡니다. 끄면 "지금 갱신"으로만 교체됩니다',
 keybox_renew_title='지금 갱신',
 keybox_renew_summary='카탈로그에서 폐기되지 않은 키박스를 받아 설치합니다',
 keybox_renewing='갱신 중…',
 keybox_renew_ok='키박스 설치됨: %1$s %2$s',
 keybox_renew_failed='키박스 갱신 실패',
 keybox_clear_title='키박스 삭제',
 keybox_clear_summary='설치된 키박스를 시스템에서 제거합니다',
 keybox_clear_confirm='설치된 키박스와 보관된 예비 키박스를 모두 삭제할까요? 새 키박스를 설치할 때까지 하드웨어 키 증명이 위장되지 않습니다.',
 keybox_cleared='키박스를 삭제했습니다',
 keybox_pool_title='보관된 키박스',
 keybox_pool_summary='%1$d개 보관 · 폐기 기록 %2$d건',
 keybox_pool_none='없음',
 teesim_state_title='TEE 시뮬레이터',
 teesim_on='사용',
 teesim_off='사용 안 함',
 teesim_keybox_title='키박스',
 teesim_targets_category='대상 앱',
 teesim_targets_all='모든 앱',
 teesim_targets_all_summary='모든 앱의 키 증명을 위장합니다. 기본은 꺼짐이며 아래 목록만 적용됩니다',
 teesim_target_add='앱 추가',
 teesim_target_remove='눌러서 삭제',
 teesim_target_remove_confirm='이 앱을 대상 목록에서 삭제할까요?',
 teesim_auto_category='자동 관리',
 teesim_auto_title='새 앱 자동 추가',
 teesim_auto_summary='새로 설치된 앱이 대상 목록에 추가되고 삭제된 앱은 제거됩니다. 설치 시 즉시, 이후 5분마다 확인합니다',
 teesim_add_all='설치된 앱 모두 추가',
 teesim_add_all_summary='지금 설치된 모든 서드파티 앱을 대상 목록에 추가합니다',
 teesim_add_all_done='%1$d개 앱을 추가했습니다',
 pif_state_title='지문 데이터',
 pif_state_none='없음',
 pif_state_user='사용자 제공',
 pif_state_fetched='자동으로 받음',
 pif_patch_title='보안 패치',
 pif_fingerprint_title='지문',
 pif_refresh_title='지금 받기',
 pif_refresh_summary='인증된 빌드 속성을 즉시 내려받습니다',
 pif_refreshing='받는 중…',
 pif_refresh_ok='지문 데이터를 갱신했습니다',
 pif_refresh_failed='받기 실패',
 pif_clear_title='지문 데이터 삭제',
 pif_clear_summary='받은 데이터와 사용자 제공 데이터를 제거합니다',
 pif_clear_confirm='지문 데이터를 삭제할까요?',
 pif_cleared='지문 데이터를 삭제했습니다',
 game_perf_summary='앱별 CPU·GPU 설정입니다. 설정은 해당 앱이 화면에 있을 때만 적용됩니다',

 game_level_entries=['절전', '균형', '기본값'],

 game_cpu_level_summaries=['클럭을 낮춰 발열을 줄이고 플레이 시간을 늘립니다', '싱글 코어와 나머지 코어를 약 80%로 제한해 오래 플레이해도 안정적입니다', '순정 클럭을 사용합니다 (발열 보호는 유지)', '싱글·멀티 코어 제한을 직접 설정합니다'],

 game_gpu_level_summaries=['그래픽 클럭을 낮춰 발열을 줄입니다', '그래픽 클럭을 약 80%로 제한해 오래 플레이할 때 발열을 줄입니다', '순정 그래픽 클럭을 사용합니다', 'GPU 클럭 제한을 직접 설정합니다'],

)
L['ja'] = dict(
 app_name='カスタム機能',
 app_summary='ゲームパフォーマンス、Play ストアの表示',
 game_perf_title='ゲームパフォーマンス',
 game_perf_on='オン',
 game_perf_off='オフ',
 game_perf_main_switch='アプリ別のパフォーマンスプロファイルを使用',
 game_mem_clean_title='ゲーム用にメモリを解放',
 game_mem_clean_summary='リストのアプリを開くとバックグラウンドアプリを終了します',
 game_apps_category='アプリ',
 game_app_add='アプリを追加',
 game_app_remove='削除',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='プロファイルはそのアプリが画面に表示されている間だけ適用されます。アプリを離れるとクロックは元に戻ります。熱保護は常に有効です。',
 game_custom_cpu_title='CPUクロック',
 game_custom_gpu_title='GPU制限',
 game_custom_range_summary='30～100%',
 game_custom_cpu_summary='カスタム %1$d%%',
 game_custom_gpu_summary='カスタム %1$d%%',
 game_custom_level='カスタム',
 play_store_category='Play ストア',
 reboot_title='再起動が必要です',
 reboot_now='再起動',
 reboot_later='後で',
 game_perf_summary='アプリ別のCPU・GPU設定です。設定はアプリが画面に表示されている間だけ適用されます',

 game_level_entries=['省電力', 'バランス', 'デフォルト'],

 game_cpu_level_summaries=['クロックを下げて発熱を抑え、プレイ時間を延ばします', 'シングルコアとその他のコアを約80%に制限し、長時間でも安定させます', '標準のクロックを使用します（熱保護は有効）', 'シングルコアとマルチコアの制限を自分で設定します'],

 game_gpu_level_summaries=['グラフィッククロックを下げて発熱を抑えます', 'グラフィッククロックを約80%に制限し、長時間でも安定させます', '標準のグラフィッククロックを使用します', 'グラフィッククロックの制限を自分で設定します'],

)
L['zh-rCN'] = dict(
 app_name='自定义功能',
 app_summary='游戏性能、Play 商店显示',
 game_perf_title='游戏性能',
 game_perf_on='开启',
 game_perf_off='关闭',
 game_perf_main_switch='使用应用性能配置',
 game_mem_clean_title='游戏内存清理',
 game_mem_clean_summary='打开列表中的应用时关闭后台应用',
 game_apps_category='应用',
 game_app_add='添加应用',
 game_app_remove='移除',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='配置仅在对应应用显示在屏幕上时生效，离开应用后频率恢复正常。温控保护始终有效。',
 game_custom_cpu_title='CPU 频率',
 game_custom_gpu_title='GPU 限制',
 game_custom_range_summary='30～100%',
 game_custom_cpu_summary='自定义 %1$d%%',
 game_custom_gpu_summary='自定义 %1$d%%',
 game_custom_level='自定义',
 play_store_category='Play 商店',
 reboot_title='需要重启',
 reboot_now='重启',
 reboot_later='稍后',
 game_perf_summary='按应用设置CPU和GPU；设置仅在该应用显示时生效',

 game_level_entries=['省电', '均衡', '默认'],

 game_cpu_level_summaries=['降低频率，减少发热，延长游戏时间', '将单核和其他核心限制在约 80%，长时间游戏更稳定', '使用原厂频率（温控仍然有效）', '自行设置单核和多核限制'],

 game_gpu_level_summaries=['降低图形频率，减少发热', '将图形频率限制在约 80%，长时间更稳定', '使用原厂图形频率', '自行设置图形频率限制'],

)
L['zh-rTW'] = dict(
 app_name='自訂功能',
 app_summary='遊戲效能、Play 商店顯示',
 game_perf_title='遊戲效能',
 game_perf_on='開啟',
 game_perf_off='關閉',
 game_perf_main_switch='使用應用程式效能設定檔',
 game_mem_clean_title='遊戲記憶體清理',
 game_mem_clean_summary='開啟清單中的應用程式時關閉背景應用程式',
 game_apps_category='應用程式',
 game_app_add='新增應用程式',
 game_app_remove='移除',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='設定檔僅在該應用程式顯示於螢幕上時生效，離開後時脈恢復正常。溫度保護始終有效。',
 game_custom_cpu_title='CPU 時脈',
 game_custom_gpu_title='GPU 限制',
 game_custom_range_summary='30～100%',
 game_custom_cpu_summary='自訂 %1$d%%',
 game_custom_gpu_summary='自訂 %1$d%%',
 game_custom_level='自訂',
 play_store_category='Play 商店',
 reboot_title='需要重新啟動',
 reboot_now='重新啟動',
 reboot_later='稍後',
 game_perf_summary='依應用設定CPU與GPU；設定僅在該應用顯示時生效',

 game_level_entries=['省電', '平衡', '預設'],

 game_cpu_level_summaries=['降低時脈，減少發熱，延長遊戲時間', '將單核與其他核心限制在約 80%，長時間遊戲更穩定', '使用原廠時脈（溫度保護仍有效）', '自行設定單核與多核限制'],

 game_gpu_level_summaries=['降低圖形時脈，減少發熱', '將圖形時脈限制在約 80%，長時間更穩定', '使用原廠圖形時脈', '自行設定圖形時脈限制'],

)
L['de'] = dict(
 app_name='Anpassungen',
 app_summary='Spielleistung, Play-Store-Identität',
 game_perf_title='Spieleleistung',
 game_perf_on='An',
 game_perf_off='Aus',
 game_perf_main_switch='Leistungsprofile pro App verwenden',
 game_mem_clean_title='Speicher für Spiele freigeben',
 game_mem_clean_summary='Schließt Hintergrund-Apps, wenn eine App aus der Liste geöffnet wird',
 game_apps_category='Apps',
 game_app_add='App hinzufügen',
 game_app_remove='Entfernen',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='Ein Profil gilt nur, solange seine App auf dem Bildschirm ist. Beim Verlassen der App kehren die Taktraten zum Normalwert zurück. Der Überhitzungsschutz bleibt immer aktiv.',
 game_custom_cpu_title='CPU-Takt',
 game_custom_gpu_title='GPU-Limit',
 game_custom_range_summary='30–100 %',
 game_custom_cpu_summary='Benutzerdefiniert %1$d%%',
 game_custom_gpu_summary='Benutzerdefiniert %1$d%%',
 game_custom_level='Benutzerdefiniert',
 play_store_category='Play Store',
 reboot_title='Neustart erforderlich',
 reboot_now='Neu starten',
 reboot_later='Später',
 game_perf_summary='CPU- und GPU-Profile pro App; die Einstellungen gelten nur, solange die App sichtbar ist',

 game_level_entries=['Energiesparen', 'Ausgewogen', 'Standard'],

 game_cpu_level_summaries=['Niedrigere Taktraten für weniger Wärme und längere Spielzeit', 'Begrenzt den Einzelkern und die übrigen Kerne auf etwa 80 % für lange, stabile Sitzungen', 'Serienmäßige Taktraten; Überhitzungsschutz bleibt aktiv', 'Einzel- und Mehrkern-Limit selbst festlegen'],

 game_gpu_level_summaries=['Niedrigerer Grafiktakt für weniger Wärme', 'Begrenzter Grafiktakt auf etwa 80 % für lange, stabile Sitzungen', 'Serienmäßiger Grafiktakt', 'Grafiktakt-Limit selbst festlegen'],

)
L['fr'] = dict(
 app_name='Fonctions personnalisées',
 app_summary='Performances de jeu, identité sur le Play Store',
 game_perf_title='Performances en jeu',
 game_perf_on='Activé',
 game_perf_off='Désactivé',
 game_perf_main_switch='Utiliser des profils de performances par application',
 game_mem_clean_title='Libérer la mémoire pour les jeux',
 game_mem_clean_summary="Ferme les applications en arrière-plan à l'ouverture d'une application de la liste",
 game_apps_category='Applications',
 game_app_add='Ajouter une application',
 game_app_remove='Retirer',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer="Un profil ne s'applique que lorsque son application est à l'écran. Les fréquences reviennent à la normale quand vous la quittez. La protection thermique reste toujours active.",
 game_custom_cpu_title='Fréquence du CPU',
 game_custom_gpu_title='Limite GPU',
 game_custom_range_summary='30–100 %',
 game_custom_cpu_summary='Personnalisé %1$d%%',
 game_custom_gpu_summary='Personnalisé %1$d%%',
 game_custom_level='Personnalisé',
 play_store_category='Play Store',
 reboot_title='Redémarrage requis',
 reboot_now='Redémarrer',
 reboot_later='Plus tard',
 game_perf_summary="Profils CPU et GPU par application ; les réglages ne s'appliquent que lorsque l'application est à l'écran",

 game_level_entries=["Économie d'énergie", 'Équilibré', 'Par défaut'],

 game_cpu_level_summaries=["Fréquences réduites pour moins de chaleur et plus d'autonomie", 'Limite le cœur principal et les autres cœurs à environ 80 % pour une fluidité durable', "Fréquences d'origine ; la protection thermique reste active", 'Définissez vous-même les limites simple et multiple'],

 game_gpu_level_summaries=['Fréquence graphique réduite pour moins de chaleur', 'Fréquence graphique limitée à environ 80 % pour une stabilité durable', "Fréquence graphique d'origine", 'Définissez vous-même la limite de fréquence graphique'],

)
L['es'] = dict(
 app_name='Funciones personalizadas',
 app_summary='Rendimiento en juegos, identidad en Play Store',
 game_perf_title='Rendimiento en juegos',
 game_perf_on='Activado',
 game_perf_off='Desactivado',
 game_perf_main_switch='Usar perfiles de rendimiento por aplicación',
 game_mem_clean_title='Liberar memoria para juegos',
 game_mem_clean_summary='Cierra las aplicaciones en segundo plano al abrir una aplicación de la lista',
 game_apps_category='Aplicaciones',
 game_app_add='Añadir aplicación',
 game_app_remove='Quitar',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='Un perfil solo se aplica mientras su aplicación está en pantalla. Las frecuencias vuelven a la normalidad al salir de la aplicación. La protección térmica siempre sigue activa.',
 game_custom_cpu_title='Frecuencia de la CPU',
 game_custom_gpu_title='Límite de GPU',
 game_custom_range_summary='30–100 %',
 game_custom_cpu_summary='Personalizado %1$d%%',
 game_custom_gpu_summary='Personalizado %1$d%%',
 game_custom_level='Personalizado',
 play_store_category='Play Store',
 reboot_title='Reinicio necesario',
 reboot_now='Reiniciar',
 reboot_later='Más tarde',
 game_perf_summary='Perfiles de CPU y GPU por aplicación; los ajustes solo se aplican mientras la aplicación está en pantalla',

 game_level_entries=['Ahorro de energía', 'Equilibrado', 'Predeterminado'],

 game_cpu_level_summaries=['Frecuencias más bajas para menos calor y más tiempo de juego', 'Limita el núcleo principal y los demás a un 80 % para sesiones largas estables', 'Frecuencias originales; la protección térmica sigue activa', 'Define tú mismo los límites de uno y varios núcleos'],

 game_gpu_level_summaries=['Frecuencia gráfica más baja para menos calor', 'Frecuencia gráfica limitada a un 80 % para sesiones largas estables', 'Frecuencia gráfica original', 'Define tú mismo el límite de frecuencia gráfica'],

)
L['it'] = dict(
 app_name='Funzioni personalizzate',
 app_summary='Prestazioni di gioco, identità sul Play Store',
 game_perf_title='Prestazioni di gioco',
 game_perf_on='Attivo',
 game_perf_off='Disattivato',
 game_perf_main_switch='Usa profili di prestazioni per app',
 game_mem_clean_title='Libera memoria per i giochi',
 game_mem_clean_summary="Chiude le app in background quando si apre un'app dell'elenco",
 game_apps_category='App',
 game_app_add='Aggiungi app',
 game_app_remove='Rimuovi',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer="Un profilo si applica solo mentre la sua app è sullo schermo. Le frequenze tornano normali quando esci dall'app. La protezione termica resta sempre attiva.",
 game_custom_cpu_title='Frequenza CPU',
 game_custom_gpu_title='Limite GPU',
 game_custom_range_summary='30–100%',
 game_custom_cpu_summary='Personalizzato %1$d%%',
 game_custom_gpu_summary='Personalizzato %1$d%%',
 game_custom_level='Personalizzato',
 play_store_category='Play Store',
 reboot_title='Riavvio necessario',
 reboot_now='Riavvia',
 reboot_later='Più tardi',
 game_perf_summary="Profili CPU e GPU per app; le impostazioni valgono solo mentre l'app è sullo schermo",

 game_level_entries=['Risparmio energetico', 'Bilanciato', 'Predefinito'],

 game_cpu_level_summaries=['Frequenze più basse per meno calore e più tempo di gioco', "Limita il core principale e gli altri all'80% per sessioni lunghe stabili", 'Frequenze originali; la protezione termica resta attiva', 'Imposta tu i limiti singolo e multi core'],

 game_gpu_level_summaries=['Frequenza grafica più bassa per meno calore', "Frequenza grafica limitata all'80% per sessioni lunghe stabili", 'Frequenza grafica originale', 'Imposta tu il limite di frequenza grafica'],

)
L['pt-rBR'] = dict(
 app_name='Funções personalizadas',
 app_summary='Desempenho em jogos, identidade na Play Store',
 game_perf_title='Desempenho em jogos',
 game_perf_on='Ativado',
 game_perf_off='Desativado',
 game_perf_main_switch='Usar perfis de desempenho por app',
 game_mem_clean_title='Liberar memória para jogos',
 game_mem_clean_summary='Fecha apps em segundo plano ao abrir um app da lista',
 game_apps_category='Apps',
 game_app_add='Adicionar app',
 game_app_remove='Remover',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='Um perfil só se aplica enquanto o app está na tela. As frequências voltam ao normal quando você sai do app. A proteção térmica continua sempre ativa.',
 game_custom_cpu_title='Frequência da CPU',
 game_custom_gpu_title='Limite da GPU',
 game_custom_range_summary='30–100%',
 game_custom_cpu_summary='Personalizado %1$d%%',
 game_custom_gpu_summary='Personalizado %1$d%%',
 game_custom_level='Personalizado',
 play_store_category='Play Store',
 reboot_title='Reinicialização necessária',
 reboot_now='Reiniciar',
 reboot_later='Mais tarde',
 game_perf_summary='Perfis de CPU e GPU por app; as configurações valem só enquanto o app está na tela',

 game_level_entries=['Economia de energia', 'Equilibrado', 'Padrão'],

 game_cpu_level_summaries=['Frequências menores para menos calor e mais tempo de jogo', 'Limita o núcleo principal e os demais a cerca de 80% para sessões longas estáveis', 'Frequências originais; a proteção térmica continua ativa', 'Defina você mesmo os limites de um e vários núcleos'],

 game_gpu_level_summaries=['Frequência gráfica menor para menos calor', 'Frequência gráfica limitada a cerca de 80% para sessões longas estáveis', 'Frequência gráfica original', 'Defina você mesmo o limite de frequência gráfica'],

)
L['ru'] = dict(
 app_name='Дополнительные функции',
 app_summary='Игровая производительность, идентичность в Play Маркете',
 game_perf_title='Производительность в играх',
 game_perf_on='Вкл.',
 game_perf_off='Выкл.',
 game_perf_main_switch='Профили производительности для приложений',
 game_mem_clean_title='Освобождать память для игр',
 game_mem_clean_summary='Закрывает фоновые приложения при запуске приложения из списка',
 game_apps_category='Приложения',
 game_app_add='Добавить приложение',
 game_app_remove='Удалить',
 game_app_levels='CPU %1$s · GPU %2$s',
 game_cpu_category='CPU',
 game_gpu_category='GPU',
 game_perf_footer='Профиль действует, только пока его приложение на экране. После выхода из приложения частоты возвращаются к норме. Защита от перегрева всегда активна.',
 game_custom_cpu_title='Частота ЦП',
 game_custom_gpu_title='Лимит GPU',
 game_custom_range_summary='30–100 %',
 game_custom_cpu_summary='Свой %1$d%%',
 game_custom_gpu_summary='Свой %1$d%%',
 game_custom_level='Свой',
 play_store_category='Play Маркет',
 reboot_title='Требуется перезапуск',
 reboot_now='Перезапустить',
 reboot_later='Позже',
 game_perf_summary='Профили ЦП и ГП для каждого приложения; настройки действуют, пока приложение на экране',

 game_level_entries=['Энергосбережение', 'Баланс', 'По умолчанию'],

 game_cpu_level_summaries=['Пониженные частоты: меньше нагрев, дольше игра', 'Ограничивает главное и остальные ядра примерно до 80 % для стабильности', 'Заводские частоты; защита от перегрева остаётся активной', 'Задайте лимиты одного и нескольких ядер вручную'],

 game_gpu_level_summaries=['Пониженная частота графики: меньше нагрев', 'Ограниченная частота графики примерно до 80 % для долгой игры', 'Заводская частота графики', 'Задайте лимит частоты графики вручную'],

)


def esc(s):
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    if s.startswith('@') or s.startswith('?'):
        s = '\\' + s
    return s


def attrs(s):
    # a literal % without positional args would be taken as a format string
    literal = re.sub(r'%\\d\\$[sd]', '', s)
    return ' formatted="false"' if '%' in literal else ''


HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
     SPDX-FileCopyrightText: 2026 The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->
<!-- Generated by tools/custom_strings.py; edit the table there. -->
<resources>
'''

en = L['en']
for lang, table in L.items():
    # Keys a translation does not carry yet fall back to English; English must
    # be complete.
    missing = [k for k, _ in KEYS if k not in table] + [a for a in ARRAYS if a not in table]
    assert not missing or lang != 'en', (lang, missing)
    out = [HEADER]
    for key, _ in KEYS:
        v = table.get(key, en[key])
        if lang != 'en' and v == en[key] and key not in ('app_name',):
            continue
        out.append(f'    <string name="{key}"{attrs(v)}>{esc(v)}</string>\n')
    for a in ARRAYS:
        items = table.get(a, en[a])
        assert len(items) == len(en[a]), (lang, a)
        out.append(f'    <string-array name="{a}">\n')
        for it in items:
            assert it.count('%') <= 1, it  # arrays are not formatted
            out.append(f'        <item>{esc(it)}</item>\n')
        out.append('    </string-array>\n')
    out.append('</resources>\n')
    d = os.path.join(RES, 'values' if lang == 'en' else 'values-' + lang)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, 'strings.xml'), 'w') as f:
        f.write(''.join(out))
    print(lang, d)
