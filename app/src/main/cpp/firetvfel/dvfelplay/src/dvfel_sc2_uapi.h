/* SPDX-License-Identifier: GPL-2.0-or-later WITH Linux-syscall-note */
/*
 * dvfel (SC2) - Dolby Vision FEL on Amlogic S905X4 (SC2) Android firmwares
 * (Dune HD, kernel 5.4.125): the player's MediaCodec decodes the base layer
 * with the original profile 7 RPU, a second MediaCodec decodes the
 * enhancement layer into an AImageReader, and dvfel hands each EL picture to
 * the Dolby Vision driver as the "dveldec" provider (vfm: dveldec -> dvel).
 * The SoC's Dolby Vision core composes BL + EL; no GPU work.
 *
 * Pairing: the kernel sees base layer frames only when the Dolby Vision
 * driver processes them, with the video composer's display time instead of
 * the packet pts. The player reports every base layer access unit it queues
 * (DVFEL_IOC_SC2_BL_HINT: packet pts + the rpu_data_crc32 of its RPU, the
 * last 32 bits before the RBSP stop bit); a frame takes the earliest pending
 * pts with its RPU's CRC (frames come in display order, earlier ones it skips
 * were dropped), and the EL picture queued with that pts. EL pictures of
 * earlier frames are dropped.
 *
 * Buffer life: an EL picture stays in the decoder's buffer until the display
 * is done with it. DVFEL_IOC_SC2_EL_QUEUE returns a release fence (sync
 * file) the player hands to AImage_deleteAsync() at once: the decoder waits
 * on it before it decodes into the buffer again. DVFEL_IOC_SC2_WAIT_DONE
 * reports the pictures the display is done with (the pacing).
 */
#ifndef _UAPI_DVFEL_SC2_H
#define _UAPI_DVFEL_SC2_H

#include <linux/ioctl.h>
#include <linux/types.h>

#define DVFEL_SC2_VERSION	"2026.10.25.4"

#define DVFEL_SC2_MAX_EL	12	/* EL pictures queued + on display */
#define DVFEL_SC2_MAX_DONE	16
#define DVFEL_SC2_MAX_HINTS	256	/* base layer access units ahead of the display */

struct dvfel_sc2_info {
	char version[32];		/* out: DVFEL_SC2_VERSION */
	__u32 caps;			/* out: reserved, 0 */
	__u32 reserved[7];
};

struct dvfel_sc2_el {
	__s32 fd;			/* in: dma-buf of the EL picture (uvm, AImage) */
	__u32 rpu_crc;			/* in: rpu_data_crc32 of its frame (logs only) */
	__s64 pts_us;			/* in: the packet pts of its base layer frame */
	__u32 id;			/* out: reported by DVFEL_IOC_SC2_WAIT_DONE */
	__u32 queued;			/* out: EL pictures queued, this one included */
	__s32 fence_fd;			/* out: release fence (sync file), -1 if none */
	__u32 reserved;
};

struct dvfel_sc2_hint {
	__s64 pts_us;			/* packet pts of a base layer access unit */
	__u32 rpu_crc;			/* rpu_data_crc32 of the RPU sent with it */
	__u32 reserved;
};

#define DVFEL_SC2_FLUSH_EL	(1 << 0)	/* the queued EL pictures */
#define DVFEL_SC2_FLUSH_HINTS	(1 << 1)	/* the base layer hints */

struct dvfel_sc2_done {
	__u32 timeout_ms;		/* in: 0 = do not wait */
	__u32 count;			/* out */
	__u32 id[DVFEL_SC2_MAX_DONE];	/* out: pictures the display is done with */
};

#define DVFEL_SC2_IOC_MAGIC	'F'
#define DVFEL_IOC_SC2_INFO	_IOR(DVFEL_SC2_IOC_MAGIC, 20, struct dvfel_sc2_info)
/* start a session on this fd: Dolby Vision FEL on, EL provider registered */
#define DVFEL_IOC_SC2_START	_IO(DVFEL_SC2_IOC_MAGIC, 21)
#define DVFEL_IOC_SC2_EL_QUEUE	_IOWR(DVFEL_SC2_IOC_MAGIC, 22, struct dvfel_sc2_el)
#define DVFEL_IOC_SC2_WAIT_DONE	_IOWR(DVFEL_SC2_IOC_MAGIC, 23, struct dvfel_sc2_done)
/* seek / flush (arg: DVFEL_SC2_FLUSH_*): queued EL pictures (not the one on display) / hints dropped */
#define DVFEL_IOC_SC2_FLUSH	_IOW(DVFEL_SC2_IOC_MAGIC, 24, __u32)
/* end the session (also on close) */
#define DVFEL_IOC_SC2_STOP	_IO(DVFEL_SC2_IOC_MAGIC, 25)
/* a base layer access unit was queued to the decoder */
#define DVFEL_IOC_SC2_BL_HINT	_IOW(DVFEL_SC2_IOC_MAGIC, 26, struct dvfel_sc2_hint)

#endif /* _UAPI_DVFEL_SC2_H */
