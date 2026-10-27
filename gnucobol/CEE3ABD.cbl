      ******************************************************************
      * Program     : CEE3ABD.CBL
      * Function    : GnuCOBOL stand-in for the z/OS Language
      *               Environment abend service CEE3ABD, which does not
      *               exist off-mainframe. Reports the user abend code
      *               and ends the run unit with a non-zero exit status.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CEE3ABD.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-ABCODE-DISP          PIC 9(4).

       LINKAGE SECTION.
       01  LS-ABCODE               PIC S9(9) BINARY.
       01  LS-TIMING               PIC S9(9) BINARY.

       PROCEDURE DIVISION USING LS-ABCODE LS-TIMING.
           MOVE LS-ABCODE TO WS-ABCODE-DISP
           DISPLAY 'CEE3ABD: USER ABEND U' WS-ABCODE-DISP
                   ' (TIMING ' LS-TIMING ')' UPON SYSERR
           MOVE 16 TO RETURN-CODE
           STOP RUN.
