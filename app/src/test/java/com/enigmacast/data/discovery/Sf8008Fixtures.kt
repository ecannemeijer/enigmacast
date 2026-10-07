package com.enigmacast.data.discovery

/** `/api/about` as an Octagon SF8008 with openATV / OpenWebif 1.5 answers it (trimmed lists). */
internal const val SF8008_ABOUT = """
{"info": {"brand": "Octagon", "model": "SF8008 4K", "boxtype": "sf8008", "machinebuild": "sf8008",
 "lcd": 0, "grabpip": 1, "chipset": "3798mv200", "mem1": "1.2 GB", "uptime": "2d 3:14",
 "webifver": "OWIF 1.5.2", "imagedistro": "openatv", "friendlyimagedistro": "openATV",
 "oever": "OE-Alliance 5.3", "imagever": "7.4", "enigmaver": "2024-05-12", "kernelver": "4.4.35",
 "fp_version": null, "friendlychipsettext": "HiSilicon Hi3798MV200",
 "tuners": [{"name": "Tuner A", "type": "Si2183 (DVB-C)", "rec": "", "live": ""}],
 "ifaces": [{"name": "eth0", "friendlynic": "Ethernet", "ip": "192.168.1.209", "mask": "255.255.255.0", "v4prefix": 24}],
 "hdd": [], "shares": [], "transcoding": false, "EX": "", "streams": [],
 "timerpipzap": false, "allow_duplicate": false, "textinputsupport": true},
 "service": {"name": "RTL 4 HD", "namespace": "", "aspect": 0, "provider": "Ziggo", "width": 1920, "height": 1080, "result": true}}
"""
