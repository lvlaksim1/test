Attribute VB_Name = "modUtilities"
Option Explicit

Public Function IsLeapYear(ByVal Year As Integer) As Boolean
    IsLeapYear = (Year Mod 4 = 0 And Year Mod 100 <> 0) Or (Year Mod 400 = 0)
End Function

Public Function SafeNumber(ByVal Value As Variant) As Double
    On Error Resume Next
    SafeNumber = CDbl(Value)
    If Err.Number <> 0 Then
        SafeNumber = 0
        Err.Clear
    End If
End Function

Public Sub LogMessage(ByVal Message As String)
    Debug.Print "[" & Format(Now, "yyyy-mm-dd hh:nn:ss") & "] " & Message
End Sub