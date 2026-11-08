#include "main.h"

#include "boot.h"
#include "irqmgr.h"
#include "sys_stacks.h"
#include "graph.h"
#include "libultra/osMesg.h"
#include "libultra/os_thread.h"
#include "jsyswrap.h"
#include "m_card.h"
#include "_mem.h"
#include "padmgr.h"
#include "libultra/setthreadpri.h"
#include "m_msg.h"
#include "Famicom/famicom.h"
#include "m_debug.h"
#include "dolphin/os.h"
#include "libforest/osreport.h"
#include "m_land.h"
#ifdef __ANDROID__
#include "quest_vr_android.h"
#define QUEST_DIAG(event) quest_vr_diag(event)
#else
#define QUEST_DIAG(event) ((void)0)
#endif

// TODO: actually add all the stacks and headers

OSThread graphThread;
static OSMessage serialMsgBuf;
static OSMessageQueue l_serialMsgQ;
u8 SegmentBaseAddress[0x40];

int ScreenWidth = SCREEN_WIDTH;
int ScreenHeight = SCREEN_HEIGHT;

extern void mainproc(void* val) {
    QUEST_DIAG("game.mainproc_entered");

    irqmgr_client_t irqClient;
    OSMessageQueue irqMgrMsgQueue;
    OSMessage irqMsgBuf[10];
    OSMessage msg;

    ScreenWidth = SCREEN_WIDTH;
    ScreenHeight = SCREEN_HEIGHT;

#ifdef TARGET_PC
    OSReport("[PC] mainproc: JW_BeginFrame/EndFrame...\n");
#endif
    JW_BeginFrame();
    JW_EndFrame();
    QUEST_DIAG("game.before_card_init");
    mCD_init_card();
    QUEST_DIAG("game.after_card_init");

    osCreateMesgQueue(&l_serialMsgQ, &serialMsgBuf, 1);
    osCreateMesgQueue(&irqMgrMsgQueue, irqMsgBuf, 10);
#ifdef TARGET_PC
    OSReport("[PC] mainproc: CreateIRQManager...\n");
#endif
    CreateIRQManager(irqmgrStack + IRQMGR_STACK_SIZE, IRQMGR_STACK_SIZE, 18, 1);
    QUEST_DIAG("game.irq_manager_created");
    irqmgr_AddClient(&irqClient, &irqMgrMsgQueue);
    memset(padmgrStack, 0xEB, PADMGR_STACK_SIZE);

#ifdef TARGET_PC
    OSReport("[PC] mainproc: padmgr_Create...\n");
#endif
    padmgr_Create(&l_serialMsgQ, 7, 15, padmgrStack + PADMGR_STACK_SIZE, PADMGR_STACK_SIZE);
    QUEST_DIAG("game.pad_manager_created");

    osCreateThread2(&graphThread, 4, graph_proc, val, graphStack + GRAPH_STACK_SIZE, GRAPH_STACK_SIZE, 8);

    JW_BeginFrame();
    JW_EndFrame();

    osStartThread(&graphThread);
    osSetThreadPri(NULL, 5);

#ifdef TARGET_PC
    OSReport("[PC] mainproc: JW_Init3...\n");
#endif
    JW_Init3();
    QUEST_DIAG("game.jw_init_complete");
#ifdef TARGET_PC
    OSReport("[PC] mainproc: mMsg_aram_init2...\n");
#endif
    mMsg_aram_init2();
    QUEST_DIAG("game.message_aram_initialized");
#ifdef TARGET_PC
    OSReport("[PC] mainproc: mLd_StartFlagOn...\n");
#endif
    mLd_StartFlagOn();
    QUEST_DIAG("game.start_flag_set");
#ifdef TARGET_PC
    OSReport("[PC] mainproc: famicom_mount_archive...\n");
#endif
    famicom_mount_archive();
    QUEST_DIAG("game.famicom_archive_mounted");

#ifdef TARGET_PC
    OSReport("[PC] mainproc: JC_JKRAramHeap_dump...\n");
#endif
    JC_JKRAramHeap_dump(JC_JKRAram_getAramHeap());
    osSetThreadPri(NULL, 13);

#ifdef TARGET_PC
    /* On PC, the graph thread was skipped (single-threaded).
     * Instead of blocking in the message loop, call graph_proc directly. */
    OSReport("[PC] mainproc: calling graph_proc directly (single-threaded)...\n");
    QUEST_DIAG("game.before_graph_proc");
    graph_proc(val);
    QUEST_DIAG("game.graph_proc_returned");
    OSReport("[PC] mainproc: graph_proc returned\n");
    {
        extern void pc_platform_shutdown(void);
        pc_platform_shutdown();
        exit(0);
    }
#else
    do {
        msg = NULL;
        while (irqMgrMsgQueue.usedCount != 0) {
            osRecvMesg(&irqMgrMsgQueue, NULL, 0);
        }

        osRecvMesg(&irqMgrMsgQueue, &msg, 1);
    } while (msg != NULL);
#endif
}

u32 entry(void) {
#ifdef TARGET_PC
    OSReport("[PC] entry() called\n");
#endif
    padmgr_Init(NULL);
    new_Debug_mode();

    SETREG(SREG, 0, 0);
#ifdef TARGET_PC
    OSReport("[PC] entry: calling mainproc...\n");
#endif
    mainproc(NULL);

    return 0;
}

void main(void) {
    OSReport("どうぶつの森 main2 開始\n");
    HotStartEntry = &entry;
}
