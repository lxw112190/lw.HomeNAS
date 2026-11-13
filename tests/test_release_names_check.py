import importlib.util,json,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('names',ROOT/'tools/name-android-artifact.py');names=importlib.util.module_from_spec(spec);spec.loader.exec_module(names)

class ArtifactNames(unittest.TestCase):
    def fixture(self,root):
        for variant in ['debug','release']:
            folder=root/variant;folder.mkdir();(folder/('app-'+variant+'.apk')).write_bytes(('signed-byte-fixture-'+variant).encode())
            (folder/'output-metadata.json').write_text(json.dumps({'elements':[{'outputFile':'app-'+variant+'.apk','versionName':'1.2.0','versionCode':22}]}))
    def test_short_names_preserve_bytes_and_update_generated_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root);outputs=names.rename(root,'1.2.0')
            self.assertEqual([f.name for f in outputs],['HomeNAS-1.2.0-debug.apk','HomeNAS-1.2.0.apk'])
            for file in outputs:
                self.assertEqual(file.read_bytes(),('signed-byte-fixture-'+file.parent.name).encode())
                self.assertEqual(json.loads((file.parent/'output-metadata.json').read_text())['elements'][0]['outputFile'],file.name)
            self.assertEqual(outputs,names.rename(root,'1.2.0'))
    def test_refuses_collision_and_invalid_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.fixture(root);(root/'release/HomeNAS-1.2.0.apk').write_bytes(b'keep')
            with self.assertRaisesRegex(ValueError,'overwrite'):names.rename(root,'1.2.0')
            self.assertTrue((root/'debug/app-debug.apk').exists());self.assertEqual((root/'release/HomeNAS-1.2.0.apk').read_bytes(),b'keep')
        for value in ['../1.2.0','1.2/0','1.2.0\n','foo']:
            with self.subTest(value=value),self.assertRaises(ValueError):names.apk_name(value)
    def test_debug_only_pr_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);folder=root/'debug';folder.mkdir();(folder/'app-debug.apk').write_bytes(b'debug')
            (folder/'output-metadata.json').write_text(json.dumps({'elements':[{'outputFile':'app-debug.apk','versionName':'1.2.0'}]}))
            self.assertEqual([p.name for p in names.rename(root,'1.2.0')],['HomeNAS-1.2.0-debug.apk'])

if __name__=='__main__':unittest.main()
