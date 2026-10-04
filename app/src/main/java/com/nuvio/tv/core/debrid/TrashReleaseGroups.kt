package com.nuvio.tv.core.debrid

/**
 * Release-group data derived from the TRaSH Guides custom formats for Radarr.
 *
 * Source: github.com/TRaSH-Guides/Guides (docs/json/radarr/cf), commit 1027cd5,
 * 2026-07-16. TRaSH Guides is MIT licensed, Copyright (c) 2021 TRaSH; this file
 * embeds group names extracted from those definitions with attribution.
 *
 * PREFERRED_LADDER orders the HQ release-group tiers best-first (Remux Tier
 * 01..03, UHD BluRay 01..03, HD BluRay 01..03, WEB 01..03), deduplicated on
 * first occurrence, so groups listed in several tiers keep their highest
 * position. EXCLUDED_GROUPS flattens the LQ, Bad Dual Groups and Generated
 * Dynamic HDR lists to literal names. "Flights" is deliberately omitted from
 * the exclusions: upstream lists it both in WEB Tier 02 and in Generated
 * Dynamic HDR, where the latter only matches together with fabricated HDR
 * metadata -- a condition a flat name list cannot express -- so the preferred
 * listing wins. Regex alternations and optionals in the upstream definitions
 * were expanded to literals; open-ended patterns (alfaHD.*, MGE\\b.*) were
 * truncated to their literal prefixes.
 *
 * Entries are matched case-insensitively against the release group parsed by
 * DirectDebridStreamFilter. To refresh, re-extract from the TRaSH repository
 * and bump DEFAULTS_VERSION so stored settings merge the additions once.
 */
object TrashReleaseGroups {

    /** Bump when the shipped defaults change so persisted settings re-merge additively. */
    const val DEFAULTS_VERSION = 2

    val PREFERRED_LADDER: List<String> = listOf(
        // Remux Tier 01
        "3L", "BiZKiT", "BLURANiUM", "BMF", "CiNEPHiLES", "FraMeSToR", "PiRAMiDHEAD", "PmP", "WiLDCAT", "ZQ",
        // Remux Tier 02
        "ATELiER", "NCmt", "playBD", "SiCFoI", "SURFINBIRD", "TEPES",
        // Remux Tier 03
        "12GaugeShotgun", "decibeL", "EPSiLON", "HiFi", "iFT", "KRaLiMaRKo", "NTb", "PTP", "SumVision", "TOA",
        "TRiToN",
        // UHD BluRay Tier 01
        "CtrlHD", "MainFrame", "DON", "W4NK3R",
        // UHD BluRay Tier 02
        "HiDt", "HQMUX",
        // UHD BluRay Tier 03
        "BHDStudio", "hallowed", "HONE", "PTer", "SPHD", "WEBDV",
        // HD BluRay Tier 01
        "BBQ", "c0kE", "Chotab", "CRiSC", "D-Z0N3", "Dariush", "EbP", "EDPH", "Geek", "LolHD", "TayTO", "TDD", "TnP",
        "VietHD", "ZoroSenpai",
        // HD BluRay Tier 02
        "EA", "HiSD", "QOQ", "SA89", "sbR",
        // HD BluRay Tier 03
        "LoRD", "playHD",
        // WEB Tier 01
        "ABBIE", "AJP69", "APEX", "PAXA", "PEXA", "XEPA", "BLUTONiUM", "BYNDR", "CMRG", "CRFW", "CRUD", "FLUX",
        "GNOME", "KiNGS", "Kitsune", "MADSKY", "NOSiViD", "NTG", "RAWR", "SiC", "TheFarm",
        // WEB Tier 02
        "dB", "Flights", "MiU", "monkee", "MZABI", "PHOENiX", "playWEB", "SMURF", "TOMMY", "XEBEC", "4KBEC", "CEBEX",
        // WEB Tier 03
        "BLOOM", "Dooky", "GNOMiSSiON", "HHWEB", "NINJACENTRAL", "NPMS", "ROCCaT", "SiGMA", "SLiGNOME", "SwAgLaNdEr"
    )

    val EXCLUDED_GROUPS: List<String> = listOf(
        // LQ
        "24xHD", "41RGB", "4K4U", "AOC", "AROMA", "aXXo", "AZAZE", "BARC0DE", "BAUCKLEY", "BdC", "beAst", "BTM",
        "C1NEM4", "C4K", "CDDHD", "CHAOS", "CHD", "CiNE", "CLEANUP", "COLLECTiVE", "CREATiVE24", "CrEwSaDe", "CTFOH",
        "d3g", "DDR", "DNL", "DRX", "E", "EPiC", "EuReKA", "FaNGDiNG0", "Feranki1980", "FGT", "FMD", "FRDS", "FS",
        "FZHD", "GalaxyRG", "GHD", "GPTHD", "HDHUB4U", "HDS", "HDT", "HDTime", "HDWinG", "iNTENSO", "iPlanet", "iVy",
        "jennaortegaUHD", "jennaortega", "JFF", "KC", "KiNGDOM", "KIRA", "L0SERNIGHT", "LAMA", "Leffe", "Liber8",
        "LiGaS", "LUCY", "MarkII", "MeGusta", "Mesc", "mHD", "mSD", "MTeam", "MT", "MySiLU", "NhaNc3", "nHD",
        "nikt0", "NoGroup", "NoGrp", "nSD", "OFT", "Pahe.ph", "Pahe", "Pahe.in", "PATOMiEL", "PRODJi", "PSA", "PTNK",
        "RARBG", "RBB", "RDN", "Rifftrax", "RU4HD", "SANTi", "Scene", "SHD", "ShieldBearer", "STUTTERSHIT",
        "SUNSCREEN", "SyncUP", "TBS", "TEKNO3D", "Tigole", "TIKO", "VISIONPLUSHDR-X", "VISIONPLUSHDR1000",
        "VISIONPLUSHDR", "WAF", "WiKi", "x0r", "YIFY", "YTS.MX", "YTS", "YTS.LT", "YTS.AG", "Zeus",
        // Bad Dual Groups
        "alfaHD", "BAT", "BlackBit", "BNd", "C.A.A", "C76", "Cory", "CYPHER", "EniaHD", "EXTREME", "FF", "FOXX",
        "G4RiS", "GUEIRA", "LCD", "MGE", "MLH", "N3G4N", "ONLYMOViE", "PD", "PTHome", "RiPER", "RK", "SiGLA", "Tars",
        "TM", "tokar86a", "TURG", "TvR", "vnlls", "WTV", "XiQUEXiQUE", "Yatogam1", "YusukeFLA", "ZigZag", "ZNM",
        // Generated Dynamic HDR
        "BiTOR", "DepraveD", "GuyZo", "BR-GuyZo", "SasukeducK", "tarunk9c", "VD0N", "VECTOR", "VisionXpert"
    )
}
