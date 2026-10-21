# Approved design acceptance

The recovered canvas contains 60 registered boards.
The approved implementation plan selects the HomeHybrid, TitleRow, and OpeningLine designs.
Seven boards show earlier or alternative concepts.
The remaining 53 references map to production components and their states.

The latest user instruction makes dark appearance the default throughout the day.
Day appearance remains an optional setting.
The implementation uses native Android controls, cached artwork, and lightweight glass backgrounds.
It does not require continuous full-screen blur or a second hardware video decoder.

## Reference map

Capture names refer to the private device-review directory.
Phone captures use the actual HTML templates with representative responses.
The native captures use real metadata or identified test media.
No screenshot is a claim that every external service will return the same data.

| Board | Implementation evidence |
| --- | --- |
| `AllApps.dc.html` | all-apps |
| `Connect.dc.html` | connect-qr |
| `EditApps.dc.html` | home-edit-apps; settings-app-editor |
| `Glance.dc.html` | home-glance |
| `HomeCinema.dc.html` | Alternative Home concept. The approved plan selected HomeHybrid. |
| `HomeDock.dc.html` | Alternative Home concept. The approved plan selected HomeHybrid. |
| `HomeDockRows.dc.html` | Alternative Home concept. The approved plan selected HomeHybrid. |
| `LongPress.dc.html` | home-card-menu |
| `Main.dc.html` | Original interface reference. |
| `PlayerControls.dc.html` | player-controls |
| `PlayerRecover.dc.html` | player-recover |
| `PlayerScrub.dc.html` | prepared-preview |
| `PlayerSources.dc.html` | player-sources |
| `PlayerTracks.dc.html` | player-tracks |
| `PlayerUpNext.dc.html` | player-up-next |
| `Screensaver.dc.html` | screensaver |
| `Search.dc.html` | search-results |
| `SearchEmpty.dc.html` | search-phone |
| `Settings.dc.html` | settings-0 |
| `TitleMovie.dc.html` | title-movie |
| `TitleRow.dc.html` | title-series |
| `TitleSplit.dc.html` | Alternative series layout. The approved plan selected TitleRow. |
| `HomeHybrid.dc.html` | home-dark; home-content; home-search |
| `HomeRest.dc.html` | home-dark |
| `HomeUp.dc.html` | home-content |
| `HomeUpPick.dc.html` | home-focused-pick |
| `HomeRestNight.dc.html` | home-dark |
| `OpeningLine.dc.html` | opening-line |
| `HomeSearch.dc.html` | home-search |
| `TitleNetflix.dc.html` | netflix-first |
| `PlayerIntro.dc.html` | skip-intro |
| `OpeningSun.dc.html` | Alternative opening. The approved plan selected OpeningLine. |
| `OpeningNap.dc.html` | Alternative opening. The approved plan selected OpeningLine. |
| `BrandIcon.dc.html` | Generated icon and banner resources |
| `TitleCastMore.dc.html` | title-cast-related |
| `PersonPage.dc.html` | person |
| `NoSources.dc.html` | title-no-sources |
| `TitleRowPT.dc.html` | title-portuguese |
| `PlayerRecap.dc.html` | player-recap |
| `PlayerStillWatching.dc.html` | player-still-watching |
| `PlayerNetwork.dc.html` | player-network |
| `PlayerSubTiming.dc.html` | player-subtitle-timing |
| `PlayerSubSynced.dc.html` | player-subtitles-synced |
| `QuickPanel.dc.html` | home-quick-settings |
| `PlayerControlsPT.dc.html` | player-controls-portuguese |
| `SearchPhone.dc.html` | search-phone |
| `SettingsAudio.dc.html` | settings-6 |
| `SettingsServices.dc.html` | settings-1 |
| `SettingsRemote.dc.html` | settings-4 |
| `SettingsUpdates.dc.html` | settings-update-available |
| `SettingsSync.dc.html` | settings-paired-tvs |
| `SettingsDay.dc.html` | settings-day |
| `PhoneTyping.dc.html` | typing-en-US; typing-pt-BR |
| `PhoneSetup.dc.html` | setup-en-US; setup-pt-BR |
| `HomeUpNext.dc.html` | home-up-next |
| `HomeDay.dc.html` | home-day |
| `HomeOffline.dc.html` | home-offline |
| `HomeNoKey.dc.html` | home-no-key |
| `ResumePrompt.dc.html` | resume-prompt |
| `CrashFallback.dc.html` | crash-fallback |

## Corrections from visual review

- Replaced the generic recovery sheet with the centered artwork card.
- Corrected vertical alignment of the crash-recovery content.
- Routed Settings to the approved inline application editor.
- Restored a visible focus action in the initial setup state.
- Removed the duplicate clear control from phone search.
- Added an explicit phone artwork fallback.
- Checked Portuguese controls and the optional day appearance.

## Platform limits

The G3 does not report a 4K AVC or HEVC decoder.
Its automatic source queue excludes those unsupported variants.
Its display offers no mode that matches 24-fps playback.
The player preserves the current display mode when a matching mode is unavailable.

Two-physical-TV activation remains outside the current authorized device scope.
The encrypted signing backup has a verified private off-computer copy.
Separate recovery-password storage remains pending.
These external actions remain recorded in [VERIFICATION.md](VERIFICATION.md).
