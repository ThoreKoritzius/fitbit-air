Pod::Spec.new do |s|
  s.name             = 'fitbit_air'
  s.version          = '0.2.1'
  s.summary          = 'Experimental CoreBluetooth haptics for Fitbit Air.'
  s.homepage         = 'https://github.com/ThoreKoritzius/fitbit-air'
  s.license          = { :type => 'MIT', :file => '../LICENSE' }
  s.author           = 'fitbit_air contributors'
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/*.swift'
  s.vendored_frameworks = 'Frameworks/AirGoldenGate.xcframework'
  s.platform         = :ios, '16.0'
  s.swift_version    = '5.0'
  s.frameworks       = 'CoreBluetooth'
  s.dependency 'Flutter'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
