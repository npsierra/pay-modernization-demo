      ******************************************************************
      * Program     : COBDATFT.CBL
      * Function    : GnuCOBOL stand-in for the CardDemo assembler
      *               routine app/asm/COBDATFT.asm (date reformatting),
      *               which cannot run off-mainframe. Same parameter
      *               (copybook CODATECN / DSECT COCDATFT, 80 bytes) and
      *               the same byte moves as the assembler:
      *                 type 1 YYYYMMDD   -> YYYY-MM-DD (out type not 2)
      *                 type 2 YYYY-MM-DD -> YYYYMMDD   (out type not 1)
      *               Only the bytes the assembler moves are changed;
      *               the rest of the output date is left as it was.
      *               Any other combination sets the error message.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    COBDATFT.

       DATA DIVISION.
       LINKAGE SECTION.
       01  LS-REC.
           05  LS-INTYPE               PIC X.
           05  LS-INPDT                PIC X(20).
           05  LS-OUTTYPE              PIC X.
           05  LS-OUTDT                PIC X(20).
           05  LS-ERRMSG               PIC X(38).

       PROCEDURE DIVISION USING LS-REC.
           EVALUATE TRUE
             WHEN LS-INTYPE = '1'
      *        VALIDIN1: CLC COINPDT+4,=C'-' / CLI COOUTYPE,C'2'
               IF LS-INPDT(5:1) = '-' OR LS-OUTTYPE = '2'
                   PERFORM 9000-INVALID
               ELSE
                   MOVE LS-INPDT(1:4) TO LS-OUTDT(1:4)
                   MOVE '-'           TO LS-OUTDT(5:1)
                   MOVE LS-INPDT(5:2) TO LS-OUTDT(6:2)
                   MOVE '-'           TO LS-OUTDT(8:1)
                   MOVE LS-INPDT(7:2) TO LS-OUTDT(9:2)
               END-IF
             WHEN LS-INTYPE = '2'
      *        VALIDIN2: the separator check is commented out in the
      *        assembler, so any characters at positions 5 and 8 pass.
               IF LS-OUTTYPE = '1'
                   PERFORM 9000-INVALID
               ELSE
                   MOVE LS-INPDT(1:4) TO LS-OUTDT(1:4)
                   MOVE LS-INPDT(6:2) TO LS-OUTDT(5:2)
                   MOVE LS-INPDT(9:2) TO LS-OUTDT(7:2)
               END-IF
             WHEN OTHER
               PERFORM 9000-INVALID
           END-EVALUATE
      *    SR R15,R15: the assembler always returns 0.
           MOVE 0 TO RETURN-CODE
           GOBACK.

       9000-INVALID.
           MOVE 'INVALID INPUT' TO LS-ERRMSG.
