      ******************************************************************
      * Program     : RUNPARM.CBL
      * Function    : Off-mainframe stand-in for JCL EXEC PGM=x,PARM='y'.
      *               z/OS passes PARM to a batch main program as a
      *               halfword length followed by the text; GnuCOBOL
      *               cannot do that for an executable, so this driver
      *               builds the same area and CALLs the program, which
      *               is compiled as a module (cobc -m) and found on
      *               COB_LIBRARY_PATH.
      * Usage       : RUNPARM <program> <parm>
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    RUNPARM.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-PROGRAM              PIC X(08).
       01  WS-PARM-TEXT            PIC X(100).
       01  WS-PARM-AREA.
           05  WS-PARM-LENGTH      PIC S9(04) COMP.
           05  WS-PARM-DATA        PIC X(100).

       PROCEDURE DIVISION.
           ACCEPT WS-PROGRAM FROM ARGUMENT-VALUE
           ACCEPT WS-PARM-TEXT FROM ARGUMENT-VALUE
           MOVE WS-PARM-TEXT TO WS-PARM-DATA
           COMPUTE WS-PARM-LENGTH =
               FUNCTION LENGTH(FUNCTION TRIM(WS-PARM-TEXT TRAILING))
           CALL WS-PROGRAM USING WS-PARM-AREA
               ON EXCEPTION
                   DISPLAY 'RUNPARM: PROGRAM NOT FOUND: ' WS-PROGRAM
                           UPON SYSERR
                   MOVE 12 TO RETURN-CODE
           END-CALL
           STOP RUN.
