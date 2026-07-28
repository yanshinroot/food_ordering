param(
    [Parameter(Mandatory = $true)][string]$ComPort,
    [Parameter(Mandatory = $false)][int]$BaudRate = 9600,
    [Parameter(Mandatory = $true)][string]$FilePath
)
$data = [System.IO.File]::ReadAllBytes($FilePath)
$port = New-Object System.IO.Ports.SerialPort $ComPort, $BaudRate, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$port.WriteTimeout = 10000
$port.Open()
try {
    $port.Write($data, 0, $data.Length)
    Start-Sleep -Milliseconds 300
} finally {
    $port.Close()
}
