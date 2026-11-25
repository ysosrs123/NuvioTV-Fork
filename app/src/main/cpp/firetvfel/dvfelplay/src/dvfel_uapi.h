/* SPDX-License-Identifier: GPL-2.0-or-later WITH Linux-syscall-note */
/*
 * dvfel userspace interface (/dev/dvfel)
 *
 * A compositor process (the player) registers pairs of linear frame buffers
 * allocated from /dev/dma_heap/heap-codecmm (Android: the ION heap
 * codec_mm_cma; the buffers must be physically contiguous). For every decoded frame dvfel
 * decompresses the base layer into in[buf] and posts a job; the compositor
 * writes the composed frame into out[buf] and reports completion. dvfel then
 * compresses out[buf] for display. Jobs are posted in display order.
 *
 * Buffer layout (both directions): width x height pixels, one little-endian
 * 32-bit word per pixel, stride = width * 4 bytes:
 *   bits  0- 9  Cr (10-bit)
 *   bits 10-19  Cb (10-bit)
 *   bits 20-29  Y  (10-bit)
 *   bits 30-31  0
 * In in[buf], chroma at even x and even y is the original 4:2:0 sample; odd
 * positions are interpolated. dvfel converts out[buf] back to 4:2:0 by
 * keeping the chroma at even x/y.
 *
 * Hardware decoded enhancement layer (Dolby Vision dual layer decoding, the
 * EL decoder feeding the "dvfelel" receiver): with el_width set, dvfel
 * decompresses the EL picture of each frame into el[buf] in the same layout
 * (el_width x el_height) and flags the job with DVFEL_JOB_EL.
 */
#ifndef _UAPI_DVFEL_H
#define _UAPI_DVFEL_H

#include <linux/ioctl.h>
#include <linux/types.h>

/* module version (/sys/module/dvfel/version); compositors may require a minimum */
#define DVFEL_VERSION		"2026.10.23.3"

#define DVFEL_MAX_BUFS		4
/* bytes needed per buffer (includes a write-MIF margin) */
#define DVFEL_BUF_SIZE(w, h)	((((w) * 4 * (h) + 256 * 1024) + 4095) & ~4095)

struct dvfel_reg_bufs {
	__u32 width;
	__u32 height;
	__u32 count;			/* 1..DVFEL_MAX_BUFS */
	__s32 in_fd[DVFEL_MAX_BUFS];	/* dma-buf fds */
	__s32 out_fd[DVFEL_MAX_BUFS];
	__u32 el_width;			/* 0: no hardware decoded EL */
	__u32 el_height;
	__s32 el_fd[DVFEL_MAX_BUFS];
};

#define DVFEL_JOB_NEW_STREAM	(1 << 0)	/* first job after start/seek */
#define DVFEL_JOB_EL		(1 << 1)	/* el[buf] holds the EL picture */
#define DVFEL_JOB_RPU_CRC	(1 << 2)	/* dvfel_job2.rpu_crc is valid */

struct dvfel_job {
	__u32 timeout_ms;		/* in: max wait */
	__u32 id;			/* out */
	__u32 buf;			/* out: buffer pair index */
	__u32 width;			/* out */
	__u32 height;			/* out */
	__u32 flags;			/* out: DVFEL_JOB_* */
	__u64 pts_us;			/* out: vframe pts_us64 */
	/*
	 * out: the access units (decode order, 16 bits, wrapping) of the BL
	 * frame and of its EL picture. They differ when the EL is coded in
	 * another picture order than the BL (paired by display order): the
	 * RPU is then the one of the EL picture's access unit.
	 */
	__u32 bl_au;
	__u32 el_au;
};

/*
 * DVFEL_IOC_WAIT_JOB2: the job plus what identifies the frame where pts_us
 * is not the packet pts (Android: the video composer's display time).
 */
struct dvfel_job2 {
	struct dvfel_job job;
	/*
	 * out: rpu_data_crc32 of the frame's Dolby Vision RPU, as given to the
	 * decoder (DVFEL_JOB_RPU_CRC): the last 32 bits before the RBSP stop
	 * bit of the RPU NAL unit, emulation prevention bytes removed.
	 */
	__u32 rpu_crc;
	__u32 seq;			/* out: frames taken since the stream start */
	__u32 out;			/* out (DVFEL_IOC_REG_BUFS2): the lin_fd to compose into */
	__u32 reserved[1];
};

#define DVFEL_DONE_COMPOSED	0	/* out[buf] holds the frame */
#define DVFEL_DONE_PASSTHROUGH	1	/* show in[buf] unchanged */

struct dvfel_job_done {
	__u32 id;
	__s32 status;			/* DVFEL_DONE_* */
};

#define DVFEL_IOC_MAGIC		'F'
#define DVFEL_IOC_REG_BUFS	_IOW(DVFEL_IOC_MAGIC, 1, struct dvfel_reg_bufs)
#define DVFEL_IOC_WAIT_JOB	_IOWR(DVFEL_IOC_MAGIC, 2, struct dvfel_job)
#define DVFEL_IOC_JOB_DONE	_IOW(DVFEL_IOC_MAGIC, 3, struct dvfel_job_done)
#define DVFEL_IOC_UNREG_BUFS	_IO(DVFEL_IOC_MAGIC, 4)
#define DVFEL_IOC_WAIT_JOB2	_IOWR(DVFEL_IOC_MAGIC, 5, struct dvfel_job2)

/*
 * DVFEL_IOC_EL_PROBE: the decoder frame (vframe) attached to a uvm dma-buf,
 * e.g. a MediaCodec output picture (Android); diagnostics.
 */
struct dvfel_el_probe {
	__s32 fd;			/* in: dma-buf fd */
	__u32 type;			/* out: vframe type, 0 without a frame */
	__u32 bitdepth;
	__u32 flag;
	__u32 width;
	__u32 height;
	__u32 comp_width;
	__u32 comp_height;
	__u32 index;
	__u32 plane_num;
	__u64 pts_us;
	__u64 head_addr;
	__u64 body_addr;
	__u32 reserved[4];
};

#define DVFEL_IOC_EL_PROBE	_IOWR(DVFEL_IOC_MAGIC, 6, struct dvfel_el_probe)

/*
 * DVFEL_IOC_EL_IMPORT (Android): decompress the decoder frame attached to a
 * uvm dma-buf (a MediaCodec output picture of the EL decoder, 10-bit AFBC)
 * into a linear buffer of the layout above (width x height, cropped from
 * the top left). Synchronous; needs a registered compositor (REG_BUFS).
 */
struct dvfel_el_import {
	__s32 src_fd;			/* in: uvm dma-buf of the decoded picture */
	__s32 dst_fd;			/* in: contiguous dma-buf, DVFEL_BUF_SIZE() */
	__u32 width;			/* in */
	__u32 height;
	__u32 us;			/* out: VICP time */
	__u32 reserved[3];
};

#define DVFEL_IOC_EL_IMPORT	_IOWR(DVFEL_IOC_MAGIC, 7, struct dvfel_el_import)

/*
 * DVFEL_IOC_REG_BUFS2: REG_BUFS where the display shows the compositor's
 * frames directly (no AFBC conversion). out_fd[] of the base is unused; the
 * compositor gives at least as many display buffers (lin_fd, same layout and
 * size as in[]) as the module has slots (/sys/module/dvfel/parameters/slots).
 * Each job names the one to write (dvfel_job2.out); the composed frame or,
 * for DVFEL_DONE_PASSTHROUGH, nothing (dvfel copies in[buf] there). A
 * display buffer may be on screen until the next jobs: the compositor never
 * writes one outside of its job. Use DVFEL_IOC_WAIT_JOB2.
 */
#define DVFEL_MAX_OUT		12

struct dvfel_reg_bufs2 {
	struct dvfel_reg_bufs base;
	__u32 out_count;
	__s32 lin_fd[DVFEL_MAX_OUT];
	__u32 reserved[4];
};

#define DVFEL_IOC_REG_BUFS2	_IOW(DVFEL_IOC_MAGIC, 8, struct dvfel_reg_bufs2)

/*
 * DVFEL_IOC_SET_LEAD (Android, video composer upstream): the registered
 * compositor's player renders its frames this many microseconds (__u32,
 * <= 500000; 0: none) before their time. dvfel holds each frame back by as
 * many whole vsyncs and shows it in the vsync it was meant for, which gives
 * the compositor that much more time. The player renders exactly
 * round(lead / vsync period) vsyncs early. The module suggests a lead in
 * /sys/module/dvfel/parameters/out_lead_ms. Until the compositor
 * unregisters.
 */
#define DVFEL_IOC_SET_LEAD	_IOW(DVFEL_IOC_MAGIC, 9, __u32)

#endif
