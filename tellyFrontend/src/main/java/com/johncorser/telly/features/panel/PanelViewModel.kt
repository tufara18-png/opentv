package com.johncorser.telly.features.panel

import com.johncorser.telly.features.epg.EpgRepository
import com.johncorser.telly.features.playback.ClockStyle
import com.johncorser.telly.features.playback.ProgramTimes
import com.johncorser.telly.features.playlist.db.ChannelDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** A focus move the row list must execute (scroll + focus the index). */
data class PanelFocusCommand(
    val version: Int,
    val index: Int,
)

/**
 * Channel-list panel state: the groups column (Favorites + All channels +
 * playlist groups + custom groups, capture 25), the channel rows of the
 * selected group with their now/next programmes, per-group focus memory,
 * and the parental gate on locked groups.
 */
class PanelViewModel(
    channelDao: ChannelDao,
    private val epgRepository: EpgRepository,
    private val clock: () -> Long,
    scope: CoroutineScope,
    private val style: ClockStyle = ClockStyle(),
    /** The parental gate + the custom groups appended after playlist groups. */
    hooks: PanelHooks = PanelHooks(),
) {
    private val lock = hooks.lock
    private val channels = channelDao.observeVisible().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val custom = hooks.customGroups.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val channelIndex =
        combine(channels, custom) { list, customList -> PanelChannelIndex.build(list, customList) }
            .stateIn(scope, SharingStarted.Eagerly, PanelChannelIndex.EMPTY)
    private val selected = MutableStateFlow(ALL_CHANNELS)
    private val instant = MutableStateFlow(clock())
    private val mutableFocusIndex = MutableStateFlow(0)
    private val mutableFocusCommand = MutableStateFlow(PanelFocusCommand(0, 0))
    private val focusMemory = mutableMapOf<String, Int>()

    val selectedGroup: StateFlow<String> = selected.asStateFlow()
    val focusIndex: StateFlow<Int> = mutableFocusIndex.asStateFlow()
    val focusCommand: StateFlow<PanelFocusCommand> = mutableFocusCommand.asStateFlow()

    /** The group awaiting a parental PIN, or null when no prompt is open. */
    val pinPrompt: StateFlow<String?> = lock.pinPrompt

    /** True when PIN prompts use the masked keyboard entry, not the wheels. */
    val keyboardPin: Boolean get() = lock.keyboardPin

    /** Panel header clock, "Sun, Sep 13, 2:53 PM" in blue (capture 47). */
    val clockText: StateFlow<String> =
        instant
            .map { ProgramTimes.clock(it, style) }
            .stateIn(scope, SharingStarted.Eagerly, "")

    val groups: StateFlow<List<String>> =
        channelIndex
            .map { it.groupNames }
            .stateIn(scope, SharingStarted.Eagerly, PanelRows.groupNames(emptyList()))

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<PanelRow>> =
        combine(channelIndex, selected, instant, mutableFocusIndex) { index, group, at, focus ->
            PanelRowsRequest(
                channels = index.channelsIn(group),
                group = group,
                at = at,
                focusIndex = focus,
            )
        }.flatMapLatest { request ->
            val from = (request.focusIndex - PANEL_EPG_PREFETCH_ROWS).coerceAtLeast(0)
            val to =
                (request.focusIndex + PANEL_EPG_PREFETCH_ROWS + 1)
                    .coerceAtMost(request.channels.size)
            val ids =
                if (from < to) request.channels.subList(from, to).mapNotNull { it.epgId } else emptyList()
            epgRepository
                .nowNext(ids, request.at)
                .map { guide -> PanelRows.build(request.channels, request.group, guide, request.at, style) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The airing programme title of a row — the channel menu's blue header. */
    fun nowTitleOf(channelId: Long): String? = rows.value.firstOrNull { it.channel.id == channelId }?.nowTitle

    /** Refreshes "now" and moves focus to [channelId] (the tuned/previous one). */
    fun openFocusedOn(channelId: Long?) {
        instant.value = clock()
        channelId?.let(::focusChannel)
    }

    /** OK on a group row; locked groups prompt for the PIN instead. */
    fun selectGroup(group: String) {
        if (group == selected.value || lock.intercept(group)) return
        applyGroup(group)
    }

    /** A verified PIN releases the pending locked group. */
    fun submitPin(pin: String) {
        lock.unlock(pin)?.let(::applyGroup)
    }

    fun dismissPinPrompt() = lock.dismiss()

    fun onRowFocused(index: Int) {
        focusMemory[selected.value] = index
        mutableFocusIndex.value = index
    }

    private fun applyGroup(group: String) {
        selected.value = group
        commandFocus(focusMemory[group] ?: 0)
    }

    /** Focus moves the LIST must execute (group switch / panel open). */
    private fun commandFocus(index: Int) {
        onRowFocused(index)
        mutableFocusCommand.update { PanelFocusCommand(it.version + 1, index) }
    }

    private fun focusChannel(channelId: Long) {
        val index =
            channelIndex.value.channelsIn(selected.value).indexOfFirst { it.id == channelId }
        if (index >= 0) {
            commandFocus(index)
        } else {
            selected.value = ALL_CHANNELS
            commandFocus(channelIndex.value.all.indexOfFirst { it.id == channelId }.coerceAtLeast(0))
        }
    }

    companion object {
        const val FAVORITES = "Favorites"
        const val ALL_CHANNELS = "All channels"
        private const val PANEL_EPG_PREFETCH_ROWS = 12
    }
}


private data class PanelRowsRequest(
    val channels: List<com.johncorser.telly.features.playlist.db.ChannelEntity>,
    val group: String,
    val at: Long,
    val focusIndex: Int,
)

private data class PanelChannelIndex(
    val all: List<com.johncorser.telly.features.playlist.db.ChannelEntity>,
    val favorites: List<com.johncorser.telly.features.playlist.db.ChannelEntity>,
    val byGroup: Map<String, List<com.johncorser.telly.features.playlist.db.ChannelEntity>>,
    val byCustom: Map<String, List<com.johncorser.telly.features.playlist.db.ChannelEntity>>,
    val groupNames: List<String>,
) {
    fun channelsIn(group: String): List<com.johncorser.telly.features.playlist.db.ChannelEntity> =
        when (group) {
            PanelViewModel.ALL_CHANNELS -> all
            PanelViewModel.FAVORITES -> favorites
            else -> byGroup[group] ?: byCustom[group].orEmpty()
        }

    companion object {
        val EMPTY = PanelChannelIndex(emptyList(), emptyList(), emptyMap(), emptyMap(), PanelRows.groupNames(emptyList()))

        fun build(
            channels: List<com.johncorser.telly.features.playlist.db.ChannelEntity>,
            custom: List<com.johncorser.telly.features.groups.CustomGroup>,
        ): PanelChannelIndex {
            val byGroup =
                channels
                    .asSequence()
                    .filter { !it.source.groupTitle.isNullOrBlank() }
                    .groupBy { it.source.groupTitle!! }
            val favorites = com.johncorser.telly.features.mylist.ChannelReorder.favorites(channels)
            val keyed = channels.associateBy(com.johncorser.telly.features.playlist.ChannelImporter::keyOf)
            val byCustom =
                custom.associate { group ->
                    group.name to group.members.mapNotNull(keyed::get)
                }
            val names =
                (
                    listOf(PanelViewModel.FAVORITES, PanelViewModel.ALL_CHANNELS) +
                        byGroup.keys + custom.map { it.name }
                ).distinct()
            return PanelChannelIndex(channels, favorites, byGroup, byCustom, names)
        }
    }
}
