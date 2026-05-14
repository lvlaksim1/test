Attribute VB_Name = "TestModule"
Option Explicit

' Тестовый модуль VBA для демонстрации основных функций

' Функция для расчета суммы чисел в диапазоне
Public Function SumRange(ByVal startNum As Long, ByVal endNum As Long) As Long
    Dim i As Long
    Dim total As Long
    
    total = 0
    For i = startNum To endNum
        total = total + i
    Next i
    
    SumRange = total
End Function

' Процедура для вывода информации о системе
Public Sub ShowSystemInfo()
    Dim msg As String
    
    msg = "Информация о системе:" & vbCrLf
    msg = msg & "Пользователь: " & Environ("Username") & vbCrLf
    msg = msg & "Дата: " & Date & vbCrLf
    msg = msg & "Время: " & Time & vbCrLf
    msg = msg & "Версия Excel: " & Application.Version & vbCrLf
    
    MsgBox msg, vbInformation, "Системная информация"
End Sub

' Функция проверки числа на четность
Public Function IsEven(ByVal number As Long) As Boolean
    IsEven = (number Mod 2 = 0)
End Function

' Процедура для работы с массивами
Public Sub ArrayExample()
    Dim numbers(1 To 5) As Integer
    Dim i As Integer
    Dim result As String
    
    ' Заполнение массива
    For i = 1 To 5
        numbers(i) = i * 10
    Next i
    
    ' Вывод элементов массива
    result = "Элементы массива:" & vbCrLf
    For i = 1 To 5
        result = result & "numbers(" & i & ") = " & numbers(i) & vbCrLf
    Next i
    
    MsgBox result, vbInformation, "Пример работы с массивом"
End Sub

' Функция расчета факториала
Public Function Factorial(ByVal n As Integer) As Long
    If n <= 1 Then
        Factorial = 1
    Else
        Factorial = n * Factorial(n - 1)
    End If
End Function

' Тестовая процедура для запуска всех тестов
Public Sub RunAllTests()
    Dim testResult As String
    
    testResult = "Результаты тестов:" & vbCrLf & vbCrLf
    
    ' Тест 1: SumRange
    testResult = testResult & "SumRange(1, 10) = " & SumRange(1, 10) & vbCrLf
    
    ' Тест 2: IsEven
    testResult = testResult & "IsEven(4) = " & IsEven(4) & vbCrLf
    testResult = testResult & "IsEven(7) = " & IsEven(7) & vbCrLf
    
    ' Тест 3: Factorial
    testResult = testResult & "Factorial(5) = " & Factorial(5) & vbCrLf
    testResult = testResult & "Factorial(10) = " & Factorial(10) & vbCrLf
    
    MsgBox testResult, vbInformation, "Результаты тестирования"
End Sub
