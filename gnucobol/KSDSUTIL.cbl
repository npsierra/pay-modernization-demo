      ******************************************************************
      * Program     : KSDSUTIL.CBL
      * Function    : Off-mainframe stand-in for IDCAMS REPRO. Loads a
      *               text (line sequential) file into a GnuCOBOL
      *               indexed file, or unloads an indexed file to text.
      * Usage       : KSDSUTIL LOAD|UNLOAD XREF|ACCT|TCATBAL
      *               KSDSUTIL UNLOAD TRAN
      * Files       : DD_TEXTFILE = text side, DD_KSDSFILE = indexed.
      *               Short text lines are space padded to the record
      *               length, so trimmed lines load as full records.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    KSDSUTIL.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TEXT-FILE ASSIGN TO TEXTFILE
                  ORGANIZATION IS LINE SEQUENTIAL
                  FILE STATUS  IS WS-TEXT-STATUS.

           SELECT XREF-FILE ASSIGN TO KSDSFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS XREF-KEY
                  FILE STATUS  IS WS-KSDS-STATUS.

           SELECT ACCT-FILE ASSIGN TO KSDSFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS ACCT-KEY
                  FILE STATUS  IS WS-KSDS-STATUS.

           SELECT TCATBAL-FILE ASSIGN TO KSDSFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS TCATBAL-KEY
                  FILE STATUS  IS WS-KSDS-STATUS.

           SELECT TRAN-FILE ASSIGN TO KSDSFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS TRAN-KEY
                  FILE STATUS  IS WS-KSDS-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  TEXT-FILE.
       01  TEXT-REC                    PIC X(350).

       FD  XREF-FILE.
       01  XREF-REC.
           05 XREF-KEY                 PIC X(16).
           05 FILLER                   PIC X(34).

       FD  ACCT-FILE.
       01  ACCT-REC.
           05 ACCT-KEY                 PIC X(11).
           05 FILLER                   PIC X(289).

       FD  TCATBAL-FILE.
       01  TCATBAL-REC.
           05 TCATBAL-KEY              PIC X(17).
           05 FILLER                   PIC X(33).

       FD  TRAN-FILE.
       01  TRAN-REC.
           05 TRAN-KEY                 PIC X(16).
           05 FILLER                   PIC X(334).

       WORKING-STORAGE SECTION.
       01  WS-MODE                     PIC X(8).
       01  WS-TYPE                     PIC X(8).
       01  WS-TEXT-STATUS              PIC XX.
       01  WS-KSDS-STATUS              PIC XX.
       01  WS-COUNT                    PIC 9(9) VALUE 0.
       01  WS-EOF                      PIC X VALUE 'N'.

       PROCEDURE DIVISION.
           ACCEPT WS-MODE FROM ARGUMENT-VALUE
           ACCEPT WS-TYPE FROM ARGUMENT-VALUE
           EVALUATE WS-MODE ALSO WS-TYPE
             WHEN 'LOAD'   ALSO 'XREF'    PERFORM 1000-LOAD-XREF
             WHEN 'LOAD'   ALSO 'ACCT'    PERFORM 1100-LOAD-ACCT
             WHEN 'LOAD'   ALSO 'TCATBAL' PERFORM 1200-LOAD-TCATBAL
             WHEN 'UNLOAD' ALSO 'XREF'    PERFORM 2000-UNLOAD-XREF
             WHEN 'UNLOAD' ALSO 'ACCT'    PERFORM 2100-UNLOAD-ACCT
             WHEN 'UNLOAD' ALSO 'TCATBAL' PERFORM 2200-UNLOAD-TCATBAL
             WHEN 'UNLOAD' ALSO 'TRAN'    PERFORM 2300-UNLOAD-TRAN
             WHEN OTHER
               DISPLAY 'USAGE: KSDSUTIL LOAD|UNLOAD XREF|ACCT|TCATBAL'
                       ' OR KSDSUTIL UNLOAD TRAN'
                       UPON SYSERR
               MOVE 8 TO RETURN-CODE
               STOP RUN
           END-EVALUATE
           DISPLAY 'KSDSUTIL ' FUNCTION TRIM(WS-MODE) ' '
                   FUNCTION TRIM(WS-TYPE) ': ' WS-COUNT ' RECORDS'
           STOP RUN.

       1000-LOAD-XREF.
           OPEN INPUT TEXT-FILE OUTPUT XREF-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM 8100-READ-TEXT
           PERFORM UNTIL WS-EOF = 'Y'
               WRITE XREF-REC FROM TEXT-REC
               PERFORM 8200-CHECK-KSDS-IO
               PERFORM 8100-READ-TEXT
           END-PERFORM
           CLOSE TEXT-FILE XREF-FILE.

       1100-LOAD-ACCT.
           OPEN INPUT TEXT-FILE OUTPUT ACCT-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM 8100-READ-TEXT
           PERFORM UNTIL WS-EOF = 'Y'
               WRITE ACCT-REC FROM TEXT-REC
               PERFORM 8200-CHECK-KSDS-IO
               PERFORM 8100-READ-TEXT
           END-PERFORM
           CLOSE TEXT-FILE ACCT-FILE.

       1200-LOAD-TCATBAL.
           OPEN INPUT TEXT-FILE OUTPUT TCATBAL-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM 8100-READ-TEXT
           PERFORM UNTIL WS-EOF = 'Y'
               WRITE TCATBAL-REC FROM TEXT-REC
               PERFORM 8200-CHECK-KSDS-IO
               PERFORM 8100-READ-TEXT
           END-PERFORM
           CLOSE TEXT-FILE TCATBAL-FILE.

       2000-UNLOAD-XREF.
           OPEN INPUT XREF-FILE OUTPUT TEXT-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM UNTIL WS-EOF = 'Y'
               READ XREF-FILE
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                   WRITE TEXT-REC FROM XREF-REC
                   ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE XREF-FILE TEXT-FILE.

       2100-UNLOAD-ACCT.
           OPEN INPUT ACCT-FILE OUTPUT TEXT-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM UNTIL WS-EOF = 'Y'
               READ ACCT-FILE
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                   WRITE TEXT-REC FROM ACCT-REC
                   ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE ACCT-FILE TEXT-FILE.

       2200-UNLOAD-TCATBAL.
           OPEN INPUT TCATBAL-FILE OUTPUT TEXT-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM UNTIL WS-EOF = 'Y'
               READ TCATBAL-FILE
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                   WRITE TEXT-REC FROM TCATBAL-REC
                   ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE TCATBAL-FILE TEXT-FILE.

       2300-UNLOAD-TRAN.
           OPEN INPUT TRAN-FILE OUTPUT TEXT-FILE
           PERFORM 8000-CHECK-OPEN
           PERFORM UNTIL WS-EOF = 'Y'
               READ TRAN-FILE
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END
                   WRITE TEXT-REC FROM TRAN-REC
                   ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE TRAN-FILE TEXT-FILE.

       8000-CHECK-OPEN.
           IF WS-TEXT-STATUS NOT = '00' OR WS-KSDS-STATUS NOT = '00'
               DISPLAY 'KSDSUTIL OPEN FAILED: TEXT=' WS-TEXT-STATUS
                       ' KSDS=' WS-KSDS-STATUS UPON SYSERR
               MOVE 12 TO RETURN-CODE
               STOP RUN
           END-IF.

       8100-READ-TEXT.
           READ TEXT-FILE
             AT END MOVE 'Y' TO WS-EOF
           END-READ.

       8200-CHECK-KSDS-IO.
           IF WS-KSDS-STATUS NOT = '00'
               DISPLAY 'KSDSUTIL WRITE FAILED: STATUS=' WS-KSDS-STATUS
                       ' KEY=' TEXT-REC(1:17) UPON SYSERR
               MOVE 12 TO RETURN-CODE
               STOP RUN
           END-IF
           ADD 1 TO WS-COUNT.
